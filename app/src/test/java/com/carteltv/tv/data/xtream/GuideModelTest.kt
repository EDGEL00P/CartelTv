package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.EmptySharedPreferences
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.live.channelsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuideModelTest {

    private val min = 60_000L
    private val t0 = 1_700_000_000_000L

    /** channelsFor() reads provider/group prefs; bind an empty in-memory store so AppPreferences.sp is live. */
    @Before fun bindEmptyPreferences() {
        AppPreferences.install(EmptySharedPreferences)
    }

    private fun entry(title: String, startMin: Int, stopMin: Int?) = XtreamClient.EpgEntry(
        title = title,
        startTs = ((t0 + startMin * min) / 1000).toString(),
        stopTs = stopMin?.let { ((t0 + it * min) / 1000).toString() },
    )

    @Test fun missingEndTakenFromNextStart() {
        val s = GuideModel.slots(listOf(entry("A", 0, null), entry("B", 45, 90)))
        assertEquals(t0 + 45 * min, s[0].stopMs)
        assertEquals(t0 + 90 * min, s[1].stopMs)
    }

    @Test fun overlappingEndIsClippedToNextStart() {
        val s = GuideModel.slots(listOf(entry("A", 0, 60), entry("B", 30, 90)))
        assertEquals(t0 + 30 * min, s[0].stopMs)
    }

    @Test fun duplicatesAndUnsortedInputAreTidied() {
        val s = GuideModel.slots(listOf(entry("B", 30, 60), entry("A", 0, 30), entry("A again", 0, 30)))
        assertEquals(listOf("A", "B"), s.map { it.title })
    }

    @Test fun entriesWithoutTimestampsAreDropped() {
        assertTrue(GuideModel.slots(listOf(XtreamClient.EpgEntry(title = "x"))).isEmpty())
    }

    @Test fun phasesFollowTheClock() {
        val s = GuideModel.Slot("x", null, t0, t0 + 30 * min)
        assertEquals(GuideModel.Phase.PAST, GuideModel.phase(s, t0 + 31 * min))
        assertEquals(GuideModel.Phase.LIVE, GuideModel.phase(s, t0 + 10 * min))
        assertEquals(GuideModel.Phase.FUTURE, GuideModel.phase(s, t0 - min))
    }

    @Test fun cellsAreClippedToTheWindow() {
        val slots = listOf(
            GuideModel.Slot("before", null, t0 - 120 * min, t0 - 90 * min),
            GuideModel.Slot("straddles start", null, t0 - 20 * min, t0 + 40 * min),
            GuideModel.Slot("inside", null, t0 + 60 * min, t0 + 90 * min),
            GuideModel.Slot("after", null, t0 + 600 * min, t0 + 630 * min),
        )
        val cells = GuideModel.cells(slots, t0, t0 + 300 * min, t0 + 10 * min)
        assertEquals(listOf("straddles start", "inside"), cells.map { it.slot.title })
        assertEquals(0f, cells[0].offsetMin, 0.01f)
        assertEquals(40f, cells[0].widthMin, 0.01f)
        assertEquals(60f, cells[1].offsetMin, 0.01f)
    }

    @Test fun tinyProgrammesStayVisible() {
        val c = GuideModel.cells(listOf(GuideModel.Slot("blip", null, t0, t0 + min)), t0, t0 + 60 * min, t0).single()
        assertEquals(GuideModel.MIN_CELL_MIN, c.widthMin, 0.01f)
    }

    @Test fun windowOpensAnHourBackOnAHalfHour() {
        val now = t0 + 7 * min
        val w = GuideModel.windowStart(now)
        assertEquals(0L, w % GuideModel.SLOT_MS)
        assertTrue(now - w in 60 * min..(90 * min))
    }

    @Test fun progressAndLeftLabels() {
        val s = GuideModel.Slot("x", null, t0, t0 + 60 * min)
        assertEquals(0.5f, GuideModel.progress(s, t0 + 30 * min), 0.001f)
        assertEquals(1f, GuideModel.progress(s, t0 + 90 * min), 0.001f)
        assertEquals("Ending now", GuideModel.leftLabel(s, t0 + 57 * min))
        assertEquals("30 min left", GuideModel.leftLabel(s, t0 + 30 * min))
        assertEquals("1 h 0 min left", GuideModel.leftLabel(s, t0))
    }

    @Test fun qualityFromName() {
        assertEquals("4K", GuideModel.quality("US: ESPN UHD"))
        assertEquals("FHD", GuideModel.quality("Sky Sports FHD"))
        assertEquals("HD", GuideModel.quality("BBC One HD"))
        assertEquals("SD", GuideModel.quality("Channel 4 SD"))
        assertEquals("8K", GuideModel.quality("NHK 8K"))
        assertNull(GuideModel.quality("Comedy Central"))
    }

    @Test fun cleanNameDropsEventSlotSuffix() {
        assertEquals("MLS LIVE 15", GuideModel.cleanName("US| MLS LIVE 15 [St. Louis vs. LAFC (2026-11-23 20:10:00)]"))
        assertEquals("PRIME US 061", GuideModel.cleanName("US| PRIME US 061 [EVENT ONLY]"))
        assertEquals("NFL NETWORK [BK]", GuideModel.cleanName("US| NFL NETWORK [BK]"))
    }

    @Test fun eventSlotReadsTitleAndEasternStart() {
        val ev = com.carteltv.tv.data.xtream.EventSlots.parse("US| MLS LIVE 13 [Vancouver vs. Salt Lake (2026-11-22 19:55:00)]")!!
        assertEquals("Vancouver vs. Salt Lake", ev.title)
        assertEquals(java.time.Instant.parse("2026-11-23T00:55:00Z").toEpochMilli(), ev.startMs)
        assertNull(com.carteltv.tv.data.xtream.EventSlots.parse("US| PRIME US 061 [EVENT ONLY]"))
    }

    @Test fun emptySlotsAreHiddenFromLists() {
        val live = XtreamClient.LiveStream(streamId = 1, name = "US| MLS LIVE 15 [A vs. B (2026-11-23 20:10:00)]", categoryId = "9")
        val empty = XtreamClient.LiveStream(streamId = 2, name = "US| PRIME US 061 [EVENT ONLY]", categoryId = "9")
        val unknown = XtreamClient.LiveStream(streamId = 3, name = "US| FLO SPORTS 106 [EVENT ONLY]", categoryId = "9")
        val espn = XtreamClient.LiveStream(streamId = 4, name = "US| ESPN+ 504 [EVENT ONLY]", categoryId = "9")
        assertEquals(listOf(live, empty, espn), channelsFor("all", listOf(live, empty, unknown, espn)))
        assertEquals(listOf(live, empty, espn), channelsFor("9", listOf(live, empty, unknown, espn)))
        assertEquals("stream.espnplusus.upcoming.cartel", com.carteltv.tv.data.xtream.EventSlots.platformGuideId(espn.name))
    }

    @Test fun liveEventsScheduleIsUpcomingAndOrdered() {
        val now = java.time.Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
        fun s(id: Long, stamp: String) = XtreamClient.LiveStream(streamId = id, name = "US| X $id [A vs. B ($stamp)]")
        val over = s(1, "2026-11-22 01:00:00")
        val late = s(2, "2026-11-22 20:00:00")
        val soon = s(3, "2026-11-22 09:00:00")
        val idle = XtreamClient.LiveStream(streamId = 4, name = "US| X 4 [EVENT ONLY]")
        assertEquals(listOf(soon, late), com.carteltv.tv.data.xtream.EventSlots.schedule(listOf(over, late, soon, idle), now))
    }

    @Test fun cleanNameDropsPrefixAndQualityNoise() {
        assertEquals("ESPN", GuideModel.cleanName("US: ESPN HD"))
        assertEquals("Sky Sports Main Event", GuideModel.cleanName("UK | Sky Sports Main Event FHD"))
        assertEquals("BBC One", GuideModel.cleanName("[UK] BBC One HEVC"))
        assertEquals("HD", GuideModel.cleanName("HD"))
    }

    @Test fun cleanNameHandlesRealPlaylistShapes() {
        assertEquals("TSN 1", GuideModel.cleanName("|CA| TSN 1 ᴴᴰ"))
        assertEquals("CNN", GuideModel.cleanName("(US) CNN [FHD]"))
        assertEquals("BBC One", GuideModel.cleanName("UK ➔ BBC One HD 50FPS"))
        assertEquals("ESPN", GuideModel.cleanName("★ US | ESPN 4K HDR"))
        assertEquals("BBC-One", GuideModel.cleanName("BBC-One HD"))
        assertEquals("Sky Sports Main Event", GuideModel.cleanName("UK - Sky Sports Main Event (HEVC)"))
        assertEquals("CTV +1", GuideModel.cleanName("CA| CTV +1 H265"))
        assertEquals("VIP Movies", GuideModel.cleanName("US| VIP Movies"))
    }

    @Test fun cleanNameKeepsRealNamesThatLookLikeTags() {
        assertEquals("WWE Raw", GuideModel.cleanName("US| WWE Raw"))
        assertEquals("ESPN", GuideModel.cleanName("US| ESPN HD RAW"))
        assertEquals("ESPN", GuideModel.cleanName("US| ESPN RAW 4K"))
        assertEquals("Sky HD+", GuideModel.cleanName("DE| Sky HD+"))
        assertEquals("Sky", GuideModel.cleanName("DE| Sky HD"))
    }

    @Test fun cleanNameDropsRegionFeedSuffix() {
        assertEquals("beIN SPORTS 1", GuideModel.cleanName("beIN SPORTS 1 ASIA - NZ"))
        assertEquals("beIN SPORTS 1", GuideModel.cleanName("beIN SPORTS 1 ASIA-NZ"))
        assertEquals("ESPN", GuideModel.cleanName("US| ESPN 4K LATIN - BR"))
        assertEquals("beIN SPORTS 2", GuideModel.cleanName("beIN SPORTS 2 EU - UK"))
    }

    @Test fun displayNameReadsLikeARealChannelName() {
        assertEquals("beIN Sports 1", GuideModel.displayName("beIN SPORTS 1 ASIA - NZ"))
        assertEquals("beIN Sports 2", GuideModel.displayName("beIN SPORTS 2 EU - UK"))
        assertEquals("MLS Live 15", GuideModel.displayName("US| MLS LIVE 15 [St. Louis vs. LAFC (2026-11-23 20:10:00)]"))
        assertEquals("Prime US 061", GuideModel.displayName("US| PRIME US 061 [EVENT ONLY]"))
        assertEquals("NFL Network [BK]", GuideModel.displayName("US| NFL NETWORK [BK]"))
        assertEquals("ESPN", GuideModel.displayName("US| ESPN HD"))
        assertEquals("Fox Sports Asia", GuideModel.displayName("Fox Sports Asia"))
        assertEquals("Sky HD+", GuideModel.displayName("DE| Sky HD+"))
    }

    @Test fun displayNameSurvivesAnotherPass() {
        // Alerts and the player both render the name, so a second pass must not change it.
        listOf("beIN SPORTS 1 ASIA - NZ", "US| NFL NETWORK [BK]", "US| ESPN HD", "DE| Sky HD+")
            .forEach { assertEquals(GuideModel.displayName(it), GuideModel.displayName(GuideModel.displayName(it))) }
    }

    @Test fun cleanNameKeepsRegionWordsInRealNames() {
        assertEquals("Fox Sports Asia", GuideModel.cleanName("Fox Sports Asia"))
        assertEquals("Sky Sports - Main Event", GuideModel.cleanName("Sky Sports - Main Event"))
        assertEquals("ESPN Asia", GuideModel.cleanName("ESPN Asia HD"))
    }

    @Test fun qualityIgnoresPlusVariants() {
        org.junit.Assert.assertNull(GuideModel.quality("Sky HD+"))
        assertEquals("FHD", GuideModel.quality("ESPN 1080"))
    }

    @Test fun cleanTitleDropsGuideAnnotations() {
        assertEquals("The Late Show", GuideModel.cleanTitle("NEW: The Late Show"))
        assertEquals("NHL Hockey: Leafs at Bruins", GuideModel.cleanTitle("LIVE: NHL Hockey: Leafs at Bruins"))
        assertEquals("Jeopardy!", GuideModel.cleanTitle("Jeopardy! (R) [CC]"))
        assertEquals("News at Six", GuideModel.cleanTitle("[New] News at Six (HD)"))
        assertEquals("Live From Studio 8", GuideModel.cleanTitle("Live From Studio 8"))
        assertEquals("New Girl", GuideModel.cleanTitle("New Girl"))
        assertEquals("NEW:", GuideModel.cleanTitle("NEW:"))
    }

    @Test fun countryTagReadsEverySpelling() {
        assertEquals("CA", GuideModel.countryTag("|CA| TSN 1"))
        assertEquals("US", GuideModel.countryTag("(US) CNN"))
        assertEquals("UK", GuideModel.countryTag("UK ➔ BBC One"))
        assertEquals("UK", GuideModel.countryTag("[UK] BBC One"))
        org.junit.Assert.assertNull(GuideModel.countryTag("BBC-One HD"))
    }

    @Test fun countryTagFromPrefix() {
        assertEquals("US", GuideModel.countryTag("US: ESPN HD"))
        assertEquals("UK", GuideModel.countryTag("[UK] BBC One"))
        assertNull(GuideModel.countryTag("Comedy Central"))
    }
}
