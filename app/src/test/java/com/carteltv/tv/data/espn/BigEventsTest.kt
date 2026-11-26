package com.carteltv.tv.data.espn

import com.carteltv.tv.ui.hero.EventReason
import com.carteltv.tv.ui.hero.bigEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BigEventsTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun game(name: String, state: String, a: String = "0", b: String = "0", start: Long = 0L) = EspnScoreboardClient.Score(
        name = name, detail = "", state = state, logoA = null, abbrA = "A", scoreA = a,
        logoB = null, abbrB = "B", scoreB = b, startMs = start,
    )

    private val noCrew: (String, EspnScoreboardClient.Score) -> Boolean = { _, _ -> false }

    private fun pick(scores: Map<String, List<EspnScoreboardClient.Score>>, fav: String = "", crew: (String, EspnScoreboardClient.Score) -> Boolean = noCrew) =
        bigEvents(scores, fav, now, crew).map { it.game.name }

    @Test fun aLiveGameInvolvingAFollowedTeamLeadsThenTheFavouriteLeagueThenTheRest() {
        val scores = mapOf(
            "NHL" to listOf(game("other", "in")),
            "NBA" to listOf(game("favourite", "in")),
            "NFL" to listOf(game("crew", "in")),
        )
        assertEquals(listOf("crew", "favourite", "other"), pick(scores, fav = "NBA") { league, _ -> league == "NFL" })
    }

    @Test fun finishedAndDistantGamesNeverQualify() {
        val scores = mapOf(
            "NBA" to listOf(
                game("done", "post"),
                game("tonight", "pre", start = now + 5 * 60 * minute),
                game("noStart", "pre", start = 0L),
            ),
        )
        assertTrue(pick(scores, fav = "NBA").isEmpty())
    }

    @Test fun aFavouriteLeagueGameStartingWithinTheHourQualifiesButLaterOnesDoNot() {
        val scores = mapOf(
            "NBA" to listOf(
                game("soon", "pre", start = now + 45 * minute),
                game("later", "pre", start = now + 120 * minute),
            ),
        )
        val events = bigEvents(scores, "NBA", now, noCrew)
        assertEquals(listOf("soon"), events.map { it.game.name })
        assertEquals(EventReason.FAVOURITE_SOON, events.single().reason)
    }

    @Test fun aFollowedTeamGetsALongerRunUpThanAFavouriteLeague() {
        val scores = mapOf("NBA" to listOf(game("crewSoon", "pre", start = now + 80 * minute)))
        assertEquals(listOf("crewSoon"), pick(scores) { _, g -> g.name == "crewSoon" })
        assertTrue(pick(scores, fav = "NBA").isEmpty())
    }

    @Test fun aGameThePreFeedHasNotFlippedYetStillCountsForAMinuteOrTwo() {
        val scores = mapOf("NBA" to listOf(game("justStarted", "pre", start = now - 3 * minute)))
        assertEquals(listOf("justStarted"), pick(scores, fav = "NBA"))
    }

    @Test fun theCloserScoreRanksFirstWithinTheSameReason() {
        val scores = mapOf(
            "NBA" to listOf(
                game("blowout", "in", a = "90", b = "50"),
                game("nailbiter", "in", a = "88", b = "87"),
            ),
        )
        assertEquals(listOf("nailbiter", "blowout"), pick(scores, fav = "NBA"))
    }

    @Test fun onlyTheLimitIsReturned() {
        val scores = mapOf("NBA" to List(6) { game("g$it", "in") })
        assertEquals(3, bigEvents(scores, "", now, noCrew).size)
        assertEquals(1, bigEvents(scores, "", now, noCrew, limit = 1).size)
    }
}
