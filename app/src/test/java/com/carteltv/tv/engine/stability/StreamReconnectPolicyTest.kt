package com.carteltv.tv.engine.stability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun doubles_then_gives_up() {
        val p = StreamReconnectPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), List(6) { p.nextDelayMs() })
        assertNull(p.nextDelayMs())
    }

    @Test
    fun jitter_stays_within_band_around_the_exact_delay() {
        val low = StreamReconnectPolicy(jitter = 0.25, random = { 0.0 })
        val high = StreamReconnectPolicy(jitter = 0.25, random = { 0.999999 })
        assertEquals(750L, low.nextDelayMs())
        assertEquals(1_249L, high.nextDelayMs())
        assertEquals(1_500L, low.nextDelayMs())
    }

    @Test
    fun jitter_keeps_the_attempt_budget() {
        val p = StreamReconnectPolicy(jitter = 0.25, random = { 0.5 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), List(6) { p.nextDelayMs() })
        assertNull(p.nextDelayMs())
    }

    @Test
    fun reset_restores_budget() {
        val p = StreamReconnectPolicy()
        repeat(6) { p.nextDelayMs() }
        p.reset()
        assertEquals(1_000L, p.nextDelayMs())
    }
}
