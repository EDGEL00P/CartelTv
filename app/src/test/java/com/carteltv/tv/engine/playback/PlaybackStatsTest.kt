package com.carteltv.tv.engine.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatsTest {
    private val stats = PlaybackStatistics("t") { 0L }

    @Test fun initialBufferingIsNotAStall() {
        stats.onState(Player.STATE_BUFFERING, 0)
        stats.onState(Player.STATE_READY, 900)
        assertEquals(0, stats.stalls)
        assertEquals(0L, stats.stallMs)
    }

    @Test fun rebufferAfterReadyCountsOneStallWithItsDuration() {
        stats.onState(Player.STATE_READY, 0)
        stats.onState(Player.STATE_BUFFERING, 1_000)
        stats.onState(Player.STATE_BUFFERING, 1_500)
        stats.onState(Player.STATE_READY, 3_000)
        assertEquals(1, stats.stalls)
        assertEquals(2_000L, stats.stallMs)
    }

    @Test fun separateStallsAccumulate() {
        stats.onState(Player.STATE_READY, 0)
        stats.onState(Player.STATE_BUFFERING, 100)
        stats.onState(Player.STATE_READY, 200)
        stats.onState(Player.STATE_BUFFERING, 1_000)
        stats.onState(Player.STATE_READY, 1_400)
        assertEquals(2, stats.stalls)
        assertEquals(500L, stats.stallMs)
    }

    @Test fun measuredThroughputAppearsInSummary() {
        val s = PlaybackStatistics("t") { 0L }
        s.recordThroughput(0L, 0L)
        s.recordThroughput(20_000_000L, 15_000L)
        assertEquals(10_666L, s.measuredKbps)
        assert(s.summary().contains("measured_kbps=10666"))
    }
}
