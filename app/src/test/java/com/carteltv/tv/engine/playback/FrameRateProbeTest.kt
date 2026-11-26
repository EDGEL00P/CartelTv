package com.carteltv.tv.engine.playback

import com.carteltv.tv.engine.playback.FrameRateProbe.Companion.snapToStandardRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateProbeTest {

    private fun feed(probe: FrameRateProbe, frameUs: Long, frames: Int = 60, start: Long = 0): Boolean {
        var full = false
        for (i in 0 until frames) full = probe.add(start + i * frameUs)
        return full
    }

    @Test fun snapsNtscFilmAndCinema() {
        assertEquals(24000f / 1001f, snapToStandardRate(23.98f), 0.0001f)
        assertEquals(24f, snapToStandardRate(24.01f), 0.0001f)
    }

    @Test fun snapsMillisecondRoundedDurations() {
        assertEquals(24000f / 1001f, snapToStandardRate(1000f / 42f), 0.0001f)
        assertEquals(30000f / 1001f, snapToStandardRate(1000f / 33f), 0.0001f)
        assertEquals(60000f / 1001f, snapToStandardRate(1000f / 17f), 0.0001f)
    }

    @Test fun leavesUnusualRatesAlone() {
        assertEquals(37f, snapToStandardRate(37f), 0f)
        assertEquals(0f, snapToStandardRate(0f), 0f)
    }

    @Test fun estimatesFromSteadyTimestamps() {
        val p = FrameRateProbe()
        assertTrue(feed(p, 41_708))
        assertEquals(24000f / 1001f, p.estimate()!!, 0.0001f)
    }

    @Test fun notReadyBeforeEnoughFrames() {
        val p = FrameRateProbe()
        assertFalse(feed(p, 40_000, frames = 20))
        assertNull(p.estimate())
    }

    @Test fun medianIgnoresASeekJump() {
        val p = FrameRateProbe()
        for (i in 0 until 30) p.add(i * 40_000L)
        for (i in 0 until 30) p.add(5_000_000_000L + i * 40_000L)
        assertEquals(25f, p.estimate()!!, 0.0001f)
    }

    @Test fun outOfOrderTimestampsAreSorted() {
        val p = FrameRateProbe()
        val order = (0 until 60).map { it * 33_367L }.shuffled(java.util.Random(7))
        order.forEach { p.add(it) }
        assertEquals(30000f / 1001f, p.estimate()!!, 0.0001f)
    }

    @Test fun rejectsAbsurdRates() {
        val p = FrameRateProbe()
        feed(p, 100)
        assertNull(p.estimate())
    }

    @Test fun resetStartsOver() {
        val p = FrameRateProbe()
        feed(p, 40_000)
        p.reset()
        assertNull(p.estimate())
    }

    @Test fun earlyEstimateWaitsForSixteenFrames() {
        val p = FrameRateProbe()
        repeat(15) { p.add(it * 20_000L) }
        assertNull(p.earlyEstimate())
        p.add(15 * 20_000L)
        assertEquals(50f, p.earlyEstimate()!!, 0.0001f)
    }

    @Test fun earlyEstimateFiltersSeekJumpAndRepeatedFrame() {
        val p = FrameRateProbe()
        repeat(14) { p.add(it * 20_000L) }
        p.add(5_000_000L)   // seek jump
        p.add(5_000_100L)   // repeated frame 100us later
        // Median interval is 20ms, so both outliers are outside 0.6x..1.6x and are dropped.
        assertEquals(50f, p.earlyEstimate()!!, 0.0001f)
    }

    @Test fun earlyEstimateHandlesUnevenTimestamps() {
        val p = FrameRateProbe()
        val gaps = (0 until 7).flatMap { listOf(18_000L, 22_000L) } + 20_000L
        var t = 0L
        gaps.forEach { gap -> p.add(t); t += gap }
        p.add(t) // 16th frame closes the last gap
        // Uneven PTS gaps average out to a clean 50 fps and all sit within the median filter.
        assertEquals(50f, p.earlyEstimate()!!, 0.0001f)
    }
}
