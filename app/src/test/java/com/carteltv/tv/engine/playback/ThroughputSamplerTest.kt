package com.carteltv.tv.engine.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasuredThroughputTest {
    @Test
    fun first_sample_only_arms_the_baseline() {
        assertNull(ThroughputSampler().sample(1_000L, 0L))
    }

    @Test
    fun measures_bits_per_second_over_the_window() {
        val t = ThroughputSampler()
        t.sample(0L, 0L)
        // 20 MB in 15 s = ~10.7 Mbps
        assertEquals(10_666_666L, t.sample(20_000_000L, 15_000L))
    }

    @Test
    fun ignores_samples_inside_the_window() {
        val t = ThroughputSampler()
        t.sample(0L, 0L)
        assertNull(t.sample(20_000_000L, 14_999L))
        assertEquals(5_333_333L, t.sample(20_000_000L, 30_000L)) // 20 MB / 30 s
    }

    @Test
    fun a_stalled_or_rewound_counter_reports_zero_not_negative() {
        val t = ThroughputSampler()
        t.sample(100L, 0L)
        assertEquals(0L, t.sample(50L, 15_000L))
    }
}
