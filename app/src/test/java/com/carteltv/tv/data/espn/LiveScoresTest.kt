package com.carteltv.tv.data.espn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LiveScoresTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun game(state: String, start: Long = 0L, score: String = "0") = EspnScoreboardClient.Score(
        name = "B at A", detail = "", state = state, logoA = null, abbrA = "A", scoreA = score,
        logoB = null, abbrB = "B", scoreB = "0", nameA = "A", nameB = "B", startMs = start,
    )

    @Test fun aLiveGameIsPolledEveryFifteenSeconds() {
        assertEquals(ScoreRefreshPolicy.LIVE_MS, ScoreRefreshPolicy.nextDelayMs(listOf(game("post"), game("in")), now))
        assertEquals(15_000L, ScoreRefreshPolicy.LIVE_MS)
    }

    @Test fun anImminentKickoffIsPolledQuickly() {
        assertEquals(ScoreRefreshPolicy.KICKOFF_SOON_MS, ScoreRefreshPolicy.nextDelayMs(listOf(game("pre", now + 10 * minute)), now))
        // Scheduled start has passed but the feed still says "pre": about to flip to live.
        assertEquals(ScoreRefreshPolicy.KICKOFF_SOON_MS, ScoreRefreshPolicy.nextDelayMs(listOf(game("pre", now - 3 * minute)), now))
    }

    @Test fun aGameLaterTodayIsPolledEveryMinute() {
        assertEquals(ScoreRefreshPolicy.TODAY_MS, ScoreRefreshPolicy.nextDelayMs(listOf(game("pre", now + 90 * minute)), now))
    }

    @Test fun nothingOnMeansRarePolling() {
        assertEquals(ScoreRefreshPolicy.QUIET_MS, ScoreRefreshPolicy.nextDelayMs(emptyList(), now))
        assertEquals(ScoreRefreshPolicy.QUIET_MS, ScoreRefreshPolicy.nextDelayMs(listOf(game("post"), game("pre", now + 9 * 3_600_000L)), now))
        // A "pre" game that should have started hours ago is stale data, not a kickoff.
        assertEquals(ScoreRefreshPolicy.QUIET_MS, ScoreRefreshPolicy.nextDelayMs(listOf(game("pre", now - 5 * 3_600_000L)), now))
    }

    @Test fun liveBeatsEverythingElse() {
        val games = listOf(game("pre", now + 5 * minute), game("in"))
        assertEquals(ScoreRefreshPolicy.LIVE_MS, ScoreRefreshPolicy.nextDelayMs(games, now))
    }

    private class FakeFeed(var board: List<EspnScoreboardClient.Score>?) : ScoreboardSource {
        val calls = AtomicInteger()
        var throwCancellation = false
        override suspend fun scoreboard(sportPath: String, date: String?) = board ?: emptyList()
        override suspend fun scoreboardOrNull(sportPath: String, date: String?): List<EspnScoreboardClient.Score>? {
            calls.incrementAndGet()
            if (throwCancellation) throw CancellationException("cancelled")
            return board
        }
    }

    private lateinit var feed: FakeFeed
    private val original = ScoreboardRepository.source

    @Before fun install() {
        ScoreboardRepository.clearCache()
        feed = FakeFeed(listOf(game("in", score = "1")))
        ScoreboardRepository.source = feed
    }

    @After fun restore() {
        ScoreboardRepository.source = original
        ScoreboardRepository.clearCache()
    }

    @Test fun aBoardSurvivesARestartThroughTheSnapshot() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("boards").toFile()
        ScoreboardRepository.snapshots = ScoreboardSnapshotStore(dir)
        try {
            ScoreboardRepository.scoreboard("x/y", date = "20261122", force = true)
            ScoreboardRepository.clearCache() // a new process starts with an empty memory cache
            assertEquals("1", ScoreboardRepository.lastKnown("x/y", "20261122")!!.single().scoreA)
            assertNull(ScoreboardRepository.lastKnown("x/y", "20261123"))
        } finally {
            ScoreboardRepository.snapshots = null
            dir.deleteRecursively()
        }
    }
    @Test fun aFailedFetchKeepsTheLastGoodScores() = runBlocking {
        assertEquals("1", ScoreboardRepository.scoreboard("x/y", force = true).single().scoreA)
        feed.board = null // the feed becomes unreachable
        val during = ScoreboardRepository.scoreboard("x/y", force = true)
        assertEquals("the scores must not vanish", 1, during.size)
        assertEquals("1", during.single().scoreA)
    }

    @Test fun aFailedFetchWithNothingCachedIsEmptyNotAnError() = runBlocking {
        feed.board = null
        assertTrue(ScoreboardRepository.scoreboard("x/y", force = true).isEmpty())
    }

    @Test fun aRealEmptyAnswerDoesReplaceTheOldBoard() = runBlocking {
        ScoreboardRepository.scoreboard("x/y", force = true)
        feed.board = emptyList() // the feed answered: no games
        assertTrue(ScoreboardRepository.scoreboard("x/y", force = true).isEmpty())
    }

    @Test fun newScoresReplaceOldOnesWhenForced() = runBlocking {
        ScoreboardRepository.scoreboard("x/y", force = true)
        feed.board = listOf(game("in", score = "2"))
        assertEquals("2", ScoreboardRepository.scoreboard("x/y", force = true).single().scoreA)
    }

    @Test fun twoPollersWithinTheShareWindowMakeOneRequest() = runBlocking {
        ScoreboardRepository.scoreboard("x/y", maxAgeMs = ScoreRefreshPolicy.SHARE_MS)
        ScoreboardRepository.scoreboard("x/y", maxAgeMs = ScoreRefreshPolicy.SHARE_MS)
        ScoreboardRepository.scoreboard("x/y", maxAgeMs = ScoreRefreshPolicy.SHARE_MS)
        assertEquals(1, feed.calls.get())
    }

    @Test fun anOldEntryIsFetchedAgain() = runBlocking {
        ScoreboardRepository.scoreboard("x/y", maxAgeMs = 45_000L)
        Thread.sleep(30)
        ScoreboardRepository.scoreboard("x/y", maxAgeMs = 10L)
        assertEquals(2, feed.calls.get())
    }

    @Test fun differentDaysAndLeaguesAreCachedSeparately() = runBlocking {
        ScoreboardRepository.scoreboard("x/y")
        ScoreboardRepository.scoreboard("x/y", date = "20261122")
        ScoreboardRepository.scoreboard("a/b")
        assertEquals(3, feed.calls.get())
    }

    @Test fun cancellationIsNeverSwallowed() {
        feed.throwCancellation = true
        var cancelled = false
        try {
            runBlocking { ScoreboardRepository.scoreboard("x/y", force = true) }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun oneSourceFailingStillGivesTheOthersBoard() = runBlocking {
        val good = FakeFeed(listOf(game("in", score = "3")))
        val bad = FakeFeed(null)
        val board = CompositeScoreboardSource(listOf(bad, good)).scoreboardOrNull("x/y")
        assertEquals("3", board!!.single().scoreA)
    }

    @Test fun everySourceFailingIsNullNotEmpty() = runBlocking {
        assertNull(CompositeScoreboardSource(listOf(FakeFeed(null), FakeFeed(null))).scoreboardOrNull("x/y"))
    }

    @Test fun aSourceThatThrowsCountsAsFailed() = runBlocking {
        val boom = object : ScoreboardSource {
            override suspend fun scoreboard(sportPath: String, date: String?): List<EspnScoreboardClient.Score> = error("boom")
            override suspend fun scoreboardOrNull(sportPath: String, date: String?): List<EspnScoreboardClient.Score>? = error("boom")
        }
        assertNull(CompositeScoreboardSource(listOf(boom)).scoreboardOrNull("x/y"))
    }

    @Test fun aCancelledSourcePropagatesThroughTheMergedSource() {
        val cancelling = FakeFeed(emptyList()).also { it.throwCancellation = true }
        var cancelled = false
        try {
            runBlocking { CompositeScoreboardSource(listOf(cancelling)).scoreboardOrNull("x/y") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}

