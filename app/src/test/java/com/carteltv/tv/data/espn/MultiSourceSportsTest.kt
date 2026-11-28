package com.carteltv.tv.data.espn

import com.carteltv.tv.data.xtream.XtreamClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSourceSportsTest {

    // Shaped like the real api-web.nhle.com/v1/score/{date} document (checked against the live API).
    private val nhlJson = """
    {"games":[
      {"id":2026020359,"gameDate":"2026-11-21","startTimeUTC":"2026-11-21T18:00:00Z","gameState":"FUT",
       "homeTeam":{"id":12,"name":{"default":"Hurricanes"},"abbrev":"CAR","record":"16-3-3","logo":"https://x/CAR.svg"},
       "awayTeam":{"id":10,"name":{"default":"Maple Leafs"},"abbrev":"TOR","record":"12-8-2","logo":"https://x/TOR.svg"},
       "tvBroadcasts":[
         {"id":754,"market":"H","countryCode":"US","network":"HHN"},
         {"id":282,"market":"N","countryCode":"CA","network":"SN"},
         {"id":324,"market":"N","countryCode":"US","network":"NHLN"},
         {"id":282,"market":"N","countryCode":"CA","network":"SN"}]},
      {"id":2,"gameState":"FINAL","startTimeUTC":"2026-11-21T00:00:00Z",
       "homeTeam":{"name":{"default":"Bruins"},"abbrev":"BOS","score":3},
       "awayTeam":{"name":{"default":"Canadiens"},"abbrev":"MTL","score":2},"tvBroadcasts":[]},
      {"id":3,"homeTeam":{"abbrev":"XXX"}}
    ]}"""

    private fun score(a: String, b: String, start: Long, vararg nets: String) = EspnScoreboardClient.Score(
        name = "$b at $a", detail = "", state = "pre", logoA = null, abbrA = a.take(3).uppercase(), scoreA = null,
        logoB = null, abbrB = b.take(3).uppercase(), scoreB = null, nameA = a, nameB = b, startMs = start,
        network = nets.firstOrNull(), networks = nets.toList(),
    )

    @Test fun nhlDocumentMapsToGames() {
        val games = NhlScoreboardClient.parse(nhlJson)
        assertEquals(2, games.size) // the third entry is malformed and is dropped alone
        val g = games[0]
        assertEquals("Maple Leafs at Hurricanes", g.name)
        assertEquals("Hurricanes", g.nameA); assertEquals("Maple Leafs", g.nameB)
        assertEquals("CAR", g.abbrA); assertEquals("pre", g.state)
        assertEquals(java.time.Instant.parse("2026-11-21T18:00:00Z").toEpochMilli(), g.startMs)
        assertEquals(1_795_284_000_000L, g.startMs)
        assertEquals(listOf("SN", "NHLN", "HHN"), g.networks) // national first, duplicates folded
        assertEquals("SN", g.network)
        assertEquals("post", games[1].state)
        assertEquals("Final", games[1].detail)
        assertEquals("3", games[1].scoreA)
    }

    @Test fun nhlSourceIgnoresOtherLeagues() = runBlocking {
        assertTrue(NhlScoreboardClient().scoreboard("basketball/nba", null).isEmpty())
    }

    @Test fun twoSourcesMergeIntoOneGameWithAllNetworks() {
        val espn = score("Hurricanes", "Maple Leafs", 1_000_000_000L, "ESPN+")
        val nhl = score("Hurricanes", "Maple Leafs", 1_000_000_000L + 30 * 60_000L, "SN", "NHLN")
        val merged = CompositeScoreboardSource.merge(listOf(listOf(espn), listOf(nhl)))
        assertEquals(1, merged.size)
        assertEquals(listOf("ESPN+", "SN", "NHLN"), merged[0].networks)
        assertEquals("ESPN+", merged[0].network)
    }

    @Test fun aGameOnlyOneSourceListsIsKept() {
        val espn = score("Hurricanes", "Maple Leafs", 1_000_000_000L, "ESPN")
        val other = score("Bruins", "Canadiens", 1_000_000_000L, "TVAS")
        val merged = CompositeScoreboardSource.merge(listOf(listOf(espn), listOf(other)))
        assertEquals(2, merged.size)
    }

    @Test fun sameTeamsOnAnotherDayAreAnotherGame() {
        val a = score("Hurricanes", "Maple Leafs", 1_000_000_000L, "ESPN")
        val b = score("Hurricanes", "Maple Leafs", 1_000_000_000L + 24 * 3_600_000L, "SN")
        assertEquals(2, CompositeScoreboardSource.merge(listOf(listOf(a), listOf(b))).size)
    }

    @Test fun anEmptyPrimaryFallsThroughToTheNextSource() {
        val nhl = score("Bruins", "Canadiens", 0L, "TVAS")
        assertEquals(listOf(nhl), CompositeScoreboardSource.merge(listOf(emptyList(), listOf(nhl))))
        assertTrue(CompositeScoreboardSource.merge(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test fun aFailingSourceNeverHidesTheOthers() = runBlocking {
        val broken = object : ScoreboardSource {
            override suspend fun scoreboard(sportPath: String, date: String?): List<EspnScoreboardClient.Score> = error("offline")
        }
        val good = object : ScoreboardSource {
            override suspend fun scoreboard(sportPath: String, date: String?) = listOf(score("Bruins", "Canadiens", 0L, "TVAS"))
        }
        assertEquals(1, CompositeScoreboardSource(listOf(broken, good)).scoreboard("hockey/nhl", null).size)
    }

    private fun ch(id: Long, name: String) = XtreamClient.LiveStream(streamId = id, name = name)

    @Test fun anyListedNetworkFindsItsChannel() {
        val game = score("Hurricanes", "Maple Leafs", 0L, "ESPN+", "SN", "NHLN")
        val ranked = GameChannelResolver.rank(game, listOf(ch(1, "CA| Sportsnet One HD"), ch(2, "US| NHL Network"), ch(3, "US| ESPN HD")))
        assertEquals(setOf(1L, 2L), ranked.map { it.stream.streamId }.toSet())
    }

    @Test fun espnPlusDoesNotMatchTheEspnChannel() {
        val game = score("Hurricanes", "Maple Leafs", 0L, "ESPN+")
        assertTrue(GameChannelResolver.rank(game, listOf(ch(3, "US| ESPN HD"))).isEmpty())
    }

    @Test fun matchReasonNamesTheNetworkThatMatched() {
        val game = score("Hurricanes", "Maple Leafs", 0L, "NHLN", "SN")
        assertEquals("SN", GameChannelResolver.rank(game, listOf(ch(1, "CA| Sportsnet One"))).first().reason)
    }

    @Test fun nothingMatchesWhenNoNetworkIsKnown() {
        val game = score("Hurricanes", "Maple Leafs", 0L)
        assertNull(GameChannelResolver.rank(game, listOf(ch(1, "CA| Sportsnet One"))).firstOrNull())
    }
}
