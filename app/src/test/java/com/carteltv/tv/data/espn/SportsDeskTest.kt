package com.carteltv.tv.data.espn

import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.engine.playback.RefreshRateSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsDeskTest {

    private fun ch(id: Long, name: String) = XtreamClient.LiveStream(streamId = id, name = name)

    private fun game(network: String?, a: String = "Lakers", b: String = "Celtics") = EspnScoreboardClient.Score(
        name = "$b at $a", detail = "", state = "pre", logoA = null, abbrA = "LAL", scoreA = null,
        logoB = null, abbrB = "BOS", scoreB = null, nameA = a, nameB = b, network = network,
    )

    private val networks = listOf(
        ch(1, "US: ESPN HD"), ch(2, "US: ESPN2 HD"), ch(3, "US: ESPN News"),
        ch(4, "UK: Sky Sports Main Event"), ch(5, "US: FOX Sports 1 FHD"), ch(6, "US: TNT HD"),
        ch(8, "Cooking Channel"),
    )
    private val eventChannel = ch(7, "NBA 01: Lakers vs Celtics")
    private val lineup = networks + eventChannel

    @Test fun exactNetworkBeatsLookalikes() {
        val top = GameChannelResolver.rank(game("ESPN"), networks).first()
        assertEquals(1L, top.stream.streamId)
    }

    @Test fun espnDoesNotMatchEspn2ByPrefix() {
        val ids = GameChannelResolver.rank(game("ESPN2"), networks).map { it.stream.streamId }
        assertEquals(2L, ids.first())
        assertTrue(1L !in ids)
    }

    @Test fun networkAliasReachesLongName() {
        val top = GameChannelResolver.rank(game("FS1"), networks).first()
        assertEquals(5L, top.stream.streamId)
    }

    @Test fun eventChannelNamedAfterBothTeamsWins() {
        val top = GameChannelResolver.rank(game("ABC"), lineup).first()
        assertEquals(7L, top.stream.streamId)
    }

    @Test fun eventChannelOutranksPlainNetworkForItsOwnGame() {
        val top = GameChannelResolver.rank(game("ESPN"), lineup).first()
        assertEquals(7L, top.stream.streamId)
    }

    @Test fun streamingOnlyNetworkProducesNoNetworkMatch() {
        val ids = GameChannelResolver.rank(game("Peacock", a = "Reds", b = "Cubs"), lineup).map { it.stream.streamId }
        assertTrue(ids.isEmpty())
    }

    @Test fun unrelatedChannelNeverMatches() {
        val ids = GameChannelResolver.rank(game("ESPN"), networks).map { it.stream.streamId }
        assertTrue(8L !in ids)
    }

    @Test fun preferredCountryBreaksTies() {
        val twins = listOf(ch(10, "UK: ESPN HD"), ch(11, "US: ESPN HD"))
        assertEquals(11L, GameChannelResolver.rank(game("ESPN"), twins, preferredCountries = listOf("US")).first().stream.streamId)
        assertEquals(10L, GameChannelResolver.rank(game("ESPN"), twins, preferredCountries = listOf("UK")).first().stream.streamId)
    }

    @Test fun qualityBreaksTiesWhenPreferred() {
        val twins = listOf(ch(20, "US: ESPN SD"), ch(21, "US: ESPN FHD"))
        assertEquals(21L, GameChannelResolver.rank(game("ESPN"), twins, preferQuality = true).first().stream.streamId)
    }

    @Test fun guideListingBoostsAChannel() {
        val streams = listOf(ch(30, "Sports Extra 3"))
        val ids = GameChannelResolver.rank(game(null), streams, epgStreamIds = setOf(30L)).map { it.stream.streamId }
        assertEquals(listOf(30L), ids)
    }

    @Test fun savedPickAlwaysRanksFirst() {
        val top = GameChannelResolver.rank(game("ESPN"), networks, pickedStreamId = 6L).first()
        assertEquals(6L, top.stream.streamId)
    }

    @Test fun picksRoundTripByNetwork() {
        val g = game("ESPN")
        val raw = GameChannelResolver.withPick("", g, 42L)
        assertEquals(42L, GameChannelResolver.savedPick(raw, g))
        assertNull(GameChannelResolver.savedPick(raw, game("FOX")))
        assertNull(GameChannelResolver.savedPick("not json", g))
    }

    @Test fun picksStayBounded() {
        var raw = ""
        repeat(100) { raw = GameChannelResolver.withPick(raw, game("NET$it"), it.toLong()) }
        assertNull(GameChannelResolver.savedPick(raw, game("NET0")))
        assertEquals(99L, GameChannelResolver.savedPick(raw, game("NET99")))
    }

    @Test fun blankLeagueListFallsBackToBuiltInSet() {
        assertEquals(LeagueCatalog.DEFAULT_LEAGUES, LeagueCatalog.enabled(""))
        assertEquals(LeagueCatalog.DEFAULT_LEAGUES, LeagueCatalog.enabled("NotALeague"))
        assertEquals(listOf("CFL"), LeagueCatalog.enabled("CFL").map { it.first })
    }

    @Test fun leagueLabelsAreUniqueAndPipeFree() {
        val labels = LeagueCatalog.ALL_LEAGUES.map { it.first }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.none { '|' in it || ',' in it })
    }

    private val modes = listOf(
        RefreshRateSync.Mode(1, 3840, 2160, 60f),
        RefreshRateSync.Mode(2, 3840, 2160, 59.94f),
        RefreshRateSync.Mode(3, 3840, 2160, 50f),
        RefreshRateSync.Mode(4, 3840, 2160, 24f),
        RefreshRateSync.Mode(5, 3840, 2160, 23.976f),
        RefreshRateSync.Mode(6, 1920, 1080, 25f),
    )

    @Test fun filmFrameRatePicksMatchingMode() {
        assertEquals(5, RefreshRateSync.pickModeId(modes, 3840, 2160, 23.976f))
        assertEquals(4, RefreshRateSync.pickModeId(modes, 3840, 2160, 24f))
    }

    @Test fun ntscAndPalPickTheirOwnRates() {
        assertEquals(2, RefreshRateSync.pickModeId(modes, 3840, 2160, 59.94f))
        assertEquals(3, RefreshRateSync.pickModeId(modes, 3840, 2160, 25f))
    }

    @Test fun neverChangesResolution() {
        assertNull(RefreshRateSync.pickModeId(modes.filter { it.height == 1080 }, 3840, 2160, 25f))
    }

    @Test fun usesAMultipleWhenNoExactRate() {
        val only = listOf(RefreshRateSync.Mode(9, 1920, 1080, 120f))
        assertEquals(9, RefreshRateSync.pickModeId(only, 1920, 1080, 24f))
    }

    @Test fun rejectsNonsenseRates() {
        assertNull(RefreshRateSync.pickModeId(modes, 3840, 2160, 0f))
        assertNull(RefreshRateSync.pickModeId(modes, 3840, 2160, 500f))
        assertNull(RefreshRateSync.pickModeId(modes, 3840, 2160, 37f))
    }

    @Test fun ntsc2997UsesDoubleRate() {
        assertEquals(2, RefreshRateSync.pickModeId(modes, 3840, 2160, 29.97f))
    }
}
