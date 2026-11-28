package com.carteltv.tv.data.espn

import com.carteltv.tv.data.espn.SportsTimeZone.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class SportsTimeZoneTest {

    private val brisbane = ZoneId.of("Australia/Brisbane")
    private val la = ZoneId.of("America/Los_Angeles")
    private val ny = ZoneId.of("America/New_York")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun game(start: Long, state: String = "pre", name: String = "B at A") = EspnScoreboardClient.Score(
        name = name, detail = "", state = state, logoA = null, abbrA = "A", scoreA = null,
        logoB = null, abbrB = "B", scoreB = null, startMs = start,
    )

    @Test fun automaticUsesTheIpZone() {
        assertEquals(brisbane, SportsTimeZone.resolve(Mode.AUTO, "Australia/Brisbane", device = la))
    }

    @Test fun automaticFallsBackToTheDeviceWhenThereIsNoUsableAnswer() {
        assertEquals(la, SportsTimeZone.resolve(Mode.AUTO, null, device = la))
        assertEquals(la, SportsTimeZone.resolve(Mode.AUTO, "", device = la))
        assertEquals(la, SportsTimeZone.resolve(Mode.AUTO, "Mars/Olympus", device = la))
        assertEquals(la, SportsTimeZone.resolve(Mode.AUTO, "UTC", device = la)) // a bare "UTC" is a missing answer
    }

    @Test fun deviceModeIgnoresTheIpAnswer() {
        assertEquals(la, SportsTimeZone.resolve(Mode.DEVICE, "Australia/Brisbane", device = la))
    }

    @Test fun aChosenZoneBeatsTheIpAndTheDevice() {
        assertEquals(ny, SportsTimeZone.resolve(Mode.MANUAL, "Australia/Brisbane", "America/New_York", device = la))
    }

    @Test fun anInvalidChoiceFallsBackToIpThenDevice() {
        assertEquals(brisbane, SportsTimeZone.resolve(Mode.MANUAL, "Australia/Brisbane", "nope", device = la))
        assertEquals(la, SportsTimeZone.resolve(Mode.MANUAL, null, "", device = la))
    }

    @Test fun modeKeysRoundTrip() {
        Mode.entries.forEach { assertEquals(it, Mode.of(it.key)) }
        assertEquals(Mode.AUTO, Mode.of("garbage"))
        assertEquals(Mode.AUTO, Mode.of(null))
    }

    @Test fun ipServiceAnswersParse() {
        assertEquals("Australia/Brisbane", SportsTimeZone.parseIpwho("""{"success":true,"timezone":{"id":"Australia/Brisbane","abbr":"AEST"}}"""))
        assertNull(SportsTimeZone.parseIpwho("""{"success":false,"message":"Invalid IP"}"""))
        assertNull(SportsTimeZone.parseIpwho("not json"))
        assertEquals("Australia/Brisbane", SportsTimeZone.parseFlat("""{"ip":"1.2.3.4","timezone":"Australia/Brisbane"}"""))
        assertNull(SportsTimeZone.parseFlat("""{"ip":"1.2.3.4"}"""))
    }

    @Test fun aDayInBrisbaneSpansTwoEspnDates() {
        // Saturday 22 Nov in Brisbane (UTC+10) runs from 21 Nov 14:00Z to 22 Nov 14:00Z = 09:00 ET to 09:00 ET.
        val w = EspnDateMapper.window(brisbane, 0, ms("2026-11-22T02:00:00Z"))
        assertEquals(ms("2026-11-21T14:00:00Z"), w.startMs)
        assertEquals(ms("2026-11-22T14:00:00Z"), w.endMs)
        assertEquals(listOf("20261121", "20261122"), EspnDateMapper.espnDates(w))
    }

    @Test fun anEasternDayIsOneEspnDate() {
        val w = EspnDateMapper.window(ny, 0, ms("2026-11-22T15:00:00Z"))
        assertEquals(listOf("20261122"), EspnDateMapper.espnDates(w))
    }

    @Test fun aLosAngelesDayCanReachIntoTomorrowsEspnDate() {
        // 22 Nov in LA is 08:00Z..08:00Z next day: 03:00 ET on the 22nd to 03:00 ET on the 23rd.
        val w = EspnDateMapper.window(la, 0, ms("2026-11-22T20:00:00Z"))
        assertEquals(listOf("20261122", "20261123"), EspnDateMapper.espnDates(w))
    }

    @Test fun offsetsMoveByWholeLocalDays() {
        val now = ms("2026-11-22T02:00:00Z")
        assertEquals(EspnDateMapper.window(brisbane, 0, now).endMs, EspnDateMapper.window(brisbane, 1, now).startMs)
        assertEquals(EspnDateMapper.window(brisbane, -1, now).endMs, EspnDateMapper.window(brisbane, 0, now).startMs)
    }

    @Test fun aDayIsTwentyFiveHoursWhenTheClocksGoBack() {
        val w = EspnDateMapper.window(ny, 0, ms("2026-11-01T12:00:00Z")) // US clocks go back on 1 Nov 2026
        assertEquals(25L * 3_600_000L, w.endMs - w.startMs)
    }

    @Test fun onlyGamesStartingInsideTheLocalDayBelong() {
        val now = ms("2026-11-22T02:00:00Z") // Saturday morning in Brisbane
        val w = EspnDateMapper.window(brisbane, 0, now)
        val fridayEveningEt = game(ms("2026-11-22T00:00:00Z"))   // 19:00 ET Fri = 10:00 Sat Brisbane: today
        val saturdayNightEt = game(ms("2026-11-22T23:00:00Z"))   // 18:00 ET Sat = Sunday morning Brisbane: not today
        assertTrue(EspnDateMapper.belongs(fridayEveningEt, w, now))
        assertFalse(EspnDateMapper.belongs(saturdayNightEt, w, now))
    }

    @Test fun aLiveGameShowsOnTodayWhateverItsStartAndAGameWithNoStartIsKept() {
        val now = ms("2026-11-22T02:00:00Z")
        val w = EspnDateMapper.window(brisbane, 0, now)
        assertTrue(EspnDateMapper.belongs(game(ms("2026-11-21T10:00:00Z"), state = "in"), w, now))
        assertTrue(EspnDateMapper.belongs(game(0L), w, now))
        // ...but a live game does not leak onto another day's window.
        val tomorrow = EspnDateMapper.window(brisbane, 1, now)
        assertFalse(EspnDateMapper.belongs(game(ms("2026-11-21T10:00:00Z"), state = "in"), tomorrow, now))
    }

    @Test fun mergeKeepsEachGameOnceEarliestFirst() {
        val a = game(2_000L, name = "X at Y")
        val b = game(1_000L, name = "P at Q")
        val merged = EspnDateMapper.merge(listOf(listOf(a, b), listOf(a.copy())))
        assertEquals(listOf("P at Q", "X at Y"), merged.map { it.name })
    }

    @Test fun labelsAndClockUseTheChosenZone() {
        val now = ms("2026-11-22T02:00:00Z")
        assertEquals("Today", EspnDateMapper.label(0, brisbane, now))
        assertEquals("Yesterday", EspnDateMapper.label(-1, brisbane, now))
        assertEquals("Tue 24", EspnDateMapper.label(2, brisbane, now, Locale.US)) // 22 Nov 2026 is a Sunday
        // 2026-11-22T00:00Z is 10:00 in Brisbane, 16:00 the day before in LA.
        assertEquals("10:00", EspnDateMapper.clock(ms("2026-11-22T00:00:00Z"), brisbane, Locale.US))
        assertEquals("16:00", EspnDateMapper.clock(ms("2026-11-22T00:00:00Z"), la, Locale.US))
        assertEquals("", EspnDateMapper.clock(0L, brisbane))
    }

    @Test fun offsetsAreLabelledForHumans() {
        assertEquals("UTC", TimeZoneCatalog.offsetLabel(0))
        assertEquals("UTC+10:00", TimeZoneCatalog.offsetLabel(10 * 3600))
        assertEquals("UTC-03:30", TimeZoneCatalog.offsetLabel(-(3 * 3600 + 1800)))
        assertEquals("UTC+05:45", TimeZoneCatalog.offsetLabel(5 * 3600 + 45 * 60)) // Kathmandu
    }

    @Test fun cityNamesReadNaturally() {
        assertEquals("Brisbane", TimeZoneCatalog.cityOf("Australia/Brisbane"))
        assertEquals("Los Angeles", TimeZoneCatalog.cityOf("America/Los_Angeles"))
        assertEquals("Argentina, Buenos Aires", TimeZoneCatalog.cityOf("America/Argentina/Buenos_Aires"))
    }

    @Test fun everyRegionListsRealZonesSortedByOffset() {
        val now = ms("2026-11-22T02:00:00Z")
        for (region in TimeZoneCatalog.REGIONS) {
            val zones = TimeZoneCatalog.zones(region, now)
            assertTrue("$region is empty", zones.isNotEmpty())
            assertTrue(zones.all { ZoneId.of(it.id) != null })
            assertEquals(zones.map { it.offsetSeconds }, zones.map { it.offsetSeconds }.sorted())
            assertEquals("duplicates in $region", zones.size, zones.map { it.id }.toSet().size)
        }
    }

    @Test fun theListHasTheZonesPeopleLookFor() {
        val now = ms("2026-11-22T02:00:00Z")
        val all = TimeZoneCatalog.REGIONS.flatMap { TimeZoneCatalog.zones(it, now) }.map { it.id }.toSet()
        listOf(
            "Australia/Brisbane", "Australia/Sydney", "Pacific/Auckland", "America/New_York", "America/Toronto",
            "America/Los_Angeles", "Europe/London", "Europe/Dublin", "Asia/Singapore", "Africa/Johannesburg", "UTC",
        ).forEach { assertTrue("missing $it", it in all) }
        assertTrue("legacy or Etc ids must not be offered", all.none { it.startsWith("Etc/") || it.startsWith("SystemV/") })
    }

    @Test fun aSingleZoneShowsItsCurrentOffset() {
        val now = ms("2026-11-22T02:00:00Z")
        assertEquals("Brisbane (UTC+10:00)", TimeZoneCatalog.label("Australia/Brisbane", now))
        // Daylight saving: New York is UTC-05:00 in November and UTC-04:00 in July.
        assertEquals("New York (UTC-05:00)", TimeZoneCatalog.label("America/New_York", now))
        assertEquals("New York (UTC-04:00)", TimeZoneCatalog.label("America/New_York", ms("2026-07-22T12:00:00Z")))
    }

    @Test fun anUnknownIdIsShownAsIs() {
        assertEquals("Nowhere/Land", TimeZoneCatalog.label("Nowhere/Land", 0L))
    }
}
