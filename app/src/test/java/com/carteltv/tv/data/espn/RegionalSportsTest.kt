package com.carteltv.tv.data.espn

import com.carteltv.tv.data.xtream.XtreamClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalSportsTest {

    private fun ch(id: Long, name: String) = XtreamClient.LiveStream(streamId = id, name = name)

    private fun game(a: String, b: String, network: String? = null, networks: List<String> = emptyList(), state: String = "pre", start: Long = 0L) =
        EspnScoreboardClient.Score(
            name = "$b at $a", detail = "", state = state, logoA = null, abbrA = a.take(3).uppercase(), scoreA = null,
            logoB = null, abbrB = b.take(3).uppercase(), scoreB = null, nameA = a, nameB = b, startMs = start,
            network = network, networks = networks,
        )

    @Test fun everyLeagueLabelAndPathIsUnique() {
        val labels = LeagueCatalog.ALL_LEAGUES.map { it.first }
        val paths = LeagueCatalog.ALL_LEAGUES.map { it.second }
        assertEquals("duplicate label", labels.size, labels.toSet().size)
        assertEquals("duplicate path", paths.size, paths.toSet().size)
        assertTrue(labels.none { '|' in it || ',' in it })
    }

    @Test fun regionPresetsOnlyNameRealLeagues() {
        val labels = LeagueCatalog.ALL_LEAGUES.map { it.first }.toSet()
        for ((region, leagues) in LeagueCatalog.REGIONS) {
            assertTrue("$region is empty", leagues.isNotEmpty())
            assertEquals("$region repeats a league", leagues.size, leagues.toSet().size)
            assertTrue("$region names unknown leagues: ${leagues.filter { it !in labels }}", leagues.all { it in labels })
        }
        assertEquals(
            listOf("Australia", "New Zealand", "United States", "Canada", "United Kingdom"),
            LeagueCatalog.REGIONS.map { it.first },
        )
    }

    @Test fun broadcasterTablesOnlyNameRealLeagues() {
        val labels = LeagueCatalog.ALL_LEAGUES.map { it.first }.toSet()
        assertTrue(BroadcastNetworks.labels().all { it in labels })
        assertTrue(BroadcastNetworks.forLeague("afl").isNotEmpty()) // case-insensitive
        assertTrue(BroadcastNetworks.forLeague("NoSuchLeague").isEmpty())
        assertTrue(BroadcastNetworks.forLeague(null).isEmpty())
    }

    @Test fun everyLeagueInTheAppHasItsNetworks() {
        // A league added to the feed list without its channels would silently find nothing to tune to.
        val without = LeagueCatalog.ALL_LEAGUES.map { it.first }.filter { BroadcastNetworks.forLeague(it).isEmpty() }
        assertTrue("leagues with no networks: $without", without.isEmpty())
    }

    @Test fun noLeagueTableNamesAStreamingService() {
        val streaming = listOf(
            "kayo", "stan", "binge", "optus", "dazn", "peacock", "paramount", "fubo", "prime video", "apple tv", "espn+",
            "b1g", "flosports", "rugbypass", "itvx", "iplayer",
        )
        for (label in LeagueCatalog.ALL_LEAGUES.map { it.first }) {
            val bad = BroadcastNetworks.forLeague(label).filter { ch -> streaming.any { ch.lowercase() == it || ch.lowercase().startsWith("$it ") } }
            assertTrue("$label lists streaming services: $bad", bad.isEmpty())
        }
    }

    @Test fun leagueOwnedNetworksAreCovered() {
        assertTrue("nfl network" in BroadcastNetworks.forLeague("NFL"))
        assertTrue("nhl network" in BroadcastNetworks.forLeague("NHL"))
        assertTrue("nba tv" in BroadcastNetworks.forLeague("NBA"))
        assertTrue("mlb network" in BroadcastNetworks.forLeague("MLB"))
        assertTrue("big ten network" in BroadcastNetworks.forLeague("NCAAF"))
        assertTrue("sec network" in BroadcastNetworks.forLeague("NCAAF"))
        assertTrue("acc network" in BroadcastNetworks.forLeague("NCAAB"))
    }

    @Test fun correctedRightsAreReflected() {
        // Checked against rights holders' published guides, November 2026.
        assertTrue("premier sports 1" in BroadcastNetworks.forLeague("NHL"))                  // NHL in the UK
        assertTrue("premier sports 1" in BroadcastNetworks.forLeague("ChampionsCup"))         // all 63 matches
        assertFalse(BroadcastNetworks.forLeague("ChampionsCup").any { it.startsWith("tnt sports") })
        assertFalse(BroadcastNetworks.forLeague("PremierLeague").any { it.startsWith("tsn") }) // Fubo has it in Canada
        assertFalse(BroadcastNetworks.forLeague("SuperRugby").any { it == "nine" || it == "channel 9" })
        assertTrue("fox footy" in BroadcastNetworks.forLeague("AFL"))
    }

    @Test fun everyRegionHasBroadcasterCoverageForMostOfItsLeagues() {
        for ((region, leagues) in LeagueCatalog.REGIONS) {
            val covered = leagues.count { BroadcastNetworks.forLeague(it).isNotEmpty() }
            assertTrue("$region covers only $covered of ${leagues.size}", covered * 10 >= leagues.size * 8)
        }
    }

    @Test fun anAflGameWithNoBroadcasterFindsFoxFootyAsACandidate() {
        val streams = listOf(ch(1, "AU| FOX FOOTY HD"), ch(2, "AU| Seven HD"), ch(3, "AU| Cooking Channel"))
        val ranked = GameChannelResolver.rank(game("Collingwood", "Carlton"), streams, leagueLabel = "AFL")
        assertEquals(setOf(1L, 2L), ranked.map { it.stream.streamId }.toSet())
        assertTrue(ranked.first { it.stream.streamId == 1L }.reason.startsWith("usually on"))
        assertEquals(0, GameChannelResolver.rank(game("Collingwood", "Carlton"), streams).size) // no league, no guess
    }

    @Test fun anNzRugbyGameFindsTheSkySportChannels() {
        val streams = listOf(ch(1, "NZ| Sky Sport 1"), ch(2, "NZ| Sky Sport 3 HD"), ch(3, "NZ| Sky Sport Select"), ch(4, "UK| Sky News"))
        val ranked = GameChannelResolver.rank(game("Blues", "Crusaders"), streams, leagueLabel = "SuperRugby")
        assertEquals(setOf(1L, 2L, 3L), ranked.map { it.stream.streamId }.toSet())
    }

    @Test fun aUkFootballGameFindsSkyAndTntChannels() {
        val streams = listOf(ch(1, "UK| Sky Sports Premier League"), ch(2, "UK| TNT Sports 1 HD"), ch(3, "UK| BBC One HD"), ch(4, "UK| Dave"))
        val ranked = GameChannelResolver.rank(game("Arsenal", "Chelsea"), streams, leagueLabel = "PremierLeague")
        assertEquals(setOf(1L, 2L), ranked.map { it.stream.streamId }.toSet())
    }

    @Test fun aCanadianHockeyGameWithOnlyEspnDataStillFindsSportsnetAndTsn() {
        val streams = listOf(ch(1, "CA| Sportsnet One"), ch(2, "CA| TSN 3"), ch(3, "CA| CBC Toronto"), ch(4, "CA| Weather Network"))
        val ranked = GameChannelResolver.rank(game("Maple Leafs", "Canadiens"), streams, leagueLabel = "NHL")
        assertTrue(ranked.map { it.stream.streamId }.containsAll(listOf(1L, 2L, 3L)))
        assertFalse(ranked.any { it.stream.streamId == 4L })
    }

    @Test fun newsAndSpanishChannelsAreNeverTheTypicalChannel() {
        val streams = listOf(ch(1, "US| ESPN News"), ch(2, "US| ESPN Deportes"), ch(3, "US| ESPN HD"))
        val ranked = GameChannelResolver.rank(game("Cowboys", "Giants"), streams, leagueLabel = "NFL")
        assertEquals(listOf(3L), ranked.map { it.stream.streamId })
    }

    @Test fun aRealNetworkOutranksATypicalOne() {
        val streams = listOf(ch(1, "US| FOX"), ch(2, "US| NBC"), ch(3, "US| CBS"))
        val ranked = GameChannelResolver.rank(game("Cowboys", "Giants", network = "NBC"), streams, leagueLabel = "NFL")
        assertEquals(2L, ranked.first().stream.streamId)
        assertEquals("NBC", ranked.first().reason)
    }

    @Test fun aGuideListingOutranksATypicalChannel() {
        val now = 1_800_000_000_000L
        val g = game("Collingwood", "Carlton", state = "in", start = now - 1_800_000L)
        val streams = listOf(ch(1, "AU| FOX FOOTY HD"), ch(2, "AU| Event 7"))
        val hits = GuideGameMatcher.rank(
            g, listOf(GuideGameMatcher.Listing(2, "AFL: Carlton v Collingwood", null, now - 1_800_000L, now + 7_200_000L)), now,
        )
        val ranked = GameChannelResolver.rank(g, streams, guideHits = hits, leagueLabel = "AFL")
        assertEquals(2L, ranked.first().stream.streamId)
    }

    @Test fun streamingServicesNeverMatchAChannel() {
        for (service in listOf("Kayo Sports", "Stan Sport", "DAZN (YES)", "Prime Video (Local)", "ESPN+", "Paramount+", "Peacock", "Apple TV", "Sky Sport Now")) {
            val g = game("Cowboys", "Giants", network = service)
            val streams = listOf(ch(1, "US| ESPN HD"), ch(2, "US| DAZN"), ch(3, "AU| Kayo"), ch(4, "NZ| Sky Sport Now"))
            assertTrue("$service matched ${GameChannelResolver.rank(g, streams).map { it.stream.name }}", GameChannelResolver.rank(g, streams).isEmpty())
        }
    }

    @Test fun aPlainEspnNetworkStillMatchesTheEspnChannel() {
        val g = game("Cowboys", "Giants", network = "ESPN")
        assertEquals(1L, GameChannelResolver.rank(g, listOf(ch(1, "US| ESPN HD"), ch(2, "US| ESPN News"))).first().stream.streamId)
    }

    @Test fun espnsLocalSpellingsReachTheirChannels() {
        val g = game("Rangers", "Bruins", network = "NESN", networks = listOf("NHL Net", "Spectrum Sports Net"))
        val streams = listOf(ch(1, "US| NESN HD"), ch(2, "US| NHL Network"), ch(3, "US| Spectrum SportsNet LA"), ch(4, "US| ESPN"))
        assertEquals(setOf(1L, 2L, 3L), GameChannelResolver.rank(g, streams).map { it.stream.streamId }.toSet())
    }
}
