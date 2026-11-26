package com.carteltv.tv.data.espn

import com.carteltv.tv.data.espn.GuideGameMatcher.Listing
import com.carteltv.tv.data.xtream.XtreamClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideGameMatcherTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun game(state: String = "in", startMs: Long = now - hour / 2, a: String = "Los Angeles Lakers", b: String = "Boston Celtics") =
        EspnScoreboardClient.Score(
            name = "$b at $a", detail = "", state = state, logoA = null, abbrA = "LAL", scoreA = null,
            logoB = null, abbrB = "BOS", scoreB = null, nameA = a, nameB = b, startMs = startMs,
        )

    private fun listing(id: Long, title: String, desc: String? = null, start: Long = now - hour / 2, stop: Long = now + 2 * hour) =
        Listing(id, title, desc, start, stop)

    @Test fun nicknameTitleMatchesAFullTeamName() {
        val hits = GuideGameMatcher.rank(game(), listOf(listing(1, "NBA Basketball: Celtics at Lakers")), now)
        assertEquals(listOf(1L), hits.map { it.streamId })
        assertTrue(hits[0].score >= 100)
    }

    @Test fun abbreviationsAndAtSignWork() {
        val hits = GuideGameMatcher.rank(game(), listOf(listing(1, "BOS @ LAL")), now)
        assertEquals(1, hits.size)
        assertTrue(hits[0].reason.contains("vs/at"))
    }

    @Test fun oneTeamAloneIsNotTheGame() {
        assertTrue(GuideGameMatcher.rank(game(), listOf(listing(1, "Lakers Courtside"), listing(2, "Celtics Insider")), now).isEmpty())
    }

    @Test fun bothTeamsOnlyInTheDescriptionStillCount() {
        val hits = GuideGameMatcher.rank(game(), listOf(listing(1, "NBA Tonight", "Live: the Celtics visit the Lakers at Crypto.com Arena.")), now)
        assertEquals(1, hits.size)
        assertTrue(hits[0].score in GuideGameMatcher.ON_GUIDE..99)
    }

    @Test fun replaysAndHighlightsDoNotCount() {
        val replays = listOf(
            listing(1, "Celtics at Lakers (Replay)"),
            listing(2, "NBA Classic: Celtics vs Lakers"),
            listing(3, "Highlights: Celtics at Lakers"),
        )
        assertTrue(GuideGameMatcher.rank(game(), replays, now).isEmpty())
    }

    @Test fun preAndPostGameShowsAreMarkedDown() {
        val live = GuideGameMatcher.rank(game(), listOf(listing(1, "Celtics at Lakers"), listing(2, "Pregame: Celtics at Lakers")), now)
        assertEquals(listOf(1L), live.map { it.streamId })
    }

    @Test fun aListingAtAnotherTimeIsRefused() {
        val yesterday = listing(1, "Celtics at Lakers", start = now - 26 * hour, stop = now - 23 * hour)
        assertTrue(GuideGameMatcher.rank(game(), listOf(yesterday), now).isEmpty())
    }

    @Test fun anUpcomingGameFitsAListingNearKickoff() {
        val start = now + 3 * hour
        val g = game(state = "pre", startMs = start)
        val near = listing(1, "Celtics at Lakers", start = start - 10 * 60_000L, stop = start + 3 * hour)
        val farLater = listing(2, "Celtics at Lakers", start = start + 20 * hour, stop = start + 23 * hour)
        assertEquals(listOf(1L), GuideGameMatcher.rank(g, listOf(near, farLater), now).map { it.streamId })
    }

    @Test fun bestListingPerChannelWinsAndOrderIsStrongestFirst() {
        val hits = GuideGameMatcher.rank(
            game(),
            listOf(
                listing(1, "NBA Tonight", "the Celtics visit the Lakers"),
                listing(1, "Celtics at Lakers"),
                listing(2, "NBA: Celtics at Lakers"),
                listing(3, "NBA: Lakers and Celtics preview", "preview"),
            ),
            now,
        )
        assertEquals(setOf(1L, 2L), hits.map { it.streamId }.toSet())
        assertEquals(1, hits.count { it.streamId == 1L })
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test fun searchTermsUseNicknamesNotCities() {
        assertEquals(listOf("lakers", "celtics"), GameChannelResolver.searchTerms(game()))
        val noNames = game().copy(nameA = null, nameB = null)
        assertEquals(listOf("lal", "bos"), GameChannelResolver.searchTerms(noNames))
    }

    @Test fun aStrongGuideListingOutranksANamedEventChannel() {
        val streams = listOf(
            XtreamClient.LiveStream(streamId = 7, name = "NBA 01: Lakers vs Celtics"),
            XtreamClient.LiveStream(streamId = 9, name = "Event Channel 12"),
        )
        val hits = GuideGameMatcher.rank(game(), listOf(listing(9, "NBA Basketball: Celtics at Lakers")), now)
        val ranked = GameChannelResolver.rank(game(), streams, guideHits = hits)
        assertEquals(9L, ranked.first().stream.streamId)
        assertTrue(ranked.first().reason.startsWith("guide:"))
    }
}
