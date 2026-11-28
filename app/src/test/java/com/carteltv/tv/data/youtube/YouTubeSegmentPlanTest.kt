package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.youtube.YouTubeApiClient.Segment
import com.carteltv.tv.data.youtube.YouTubeSegmentPlan.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSegmentPlanTest {

    private fun skip(a: Double, b: Double, c: String = "sponsor") = Segment(a, b, c, "skip")

    @Test
    fun overlapping_and_adjacent_skips_merge_into_one_seek() {
        val p = YouTubeSegmentPlan(listOf(skip(10.0, 20.0), skip(15.0, 30.0), skip(30.3, 40.0)))
        assertEquals(1, p.skips.size)
        assertEquals(10.0, p.skips[0].startSec, 0.0)
        assertEquals(40.0, p.skips[0].endSec, 0.0)
    }

    @Test
    fun skip_fires_once_then_rearms_after_rewind() {
        val p = YouTubeSegmentPlan(listOf(skip(10.0, 20.0)))
        val first = p.step(10.1).single() as Decision.Seek
        assertEquals(20.05, first.toSec, 1e-9)
        assertTrue(p.step(10.2).isEmpty())
        assertTrue(p.step(5.0).isEmpty())
        assertTrue(p.step(10.1).single() is Decision.Seek)
    }

    @Test
    fun full_label_is_not_a_time_range() {
        val p = YouTubeSegmentPlan(listOf(Segment(0.0, 0.0, "selfpromo", "full")))
        assertTrue(p.isEmpty)
        assertEquals("selfpromo", p.fullVideoLabel)
    }

    @Test
    fun mute_enters_and_leaves() {
        val p = YouTubeSegmentPlan(listOf(Segment(5.0, 9.0, "music_offtopic", "mute")))
        val enter = p.step(6.0).single() as Decision.Mute
        assertTrue(enter.entered)
        assertTrue(p.step(7.0).isEmpty())
        val leave = p.step(9.5).single() as Decision.Mute
        assertNull(leave.segment)
    }

    @Test
    fun delay_scales_to_next_boundary() {
        val p = YouTubeSegmentPlan(listOf(skip(100.0, 110.0)))
        assertEquals(1_000L, p.nextDelayMs(0.0))
        assertEquals(440L, p.nextDelayMs(99.5))
        assertEquals(80L, p.nextDelayMs(99.99))
    }

    @Test
    fun tiny_segments_are_dropped() {
        assertTrue(YouTubeSegmentPlan(listOf(skip(1.0, 1.1))).isEmpty)
    }
}
