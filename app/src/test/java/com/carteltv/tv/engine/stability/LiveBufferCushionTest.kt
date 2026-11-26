package com.carteltv.tv.engine.stability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBufferCushionTest {
    @Test
    fun startup_fill_does_not_arm() {
        val c = LiveBufferCushion()
        // A fresh buffer starts near zero and ramps up; none of these may arm the cushion.
        repeat(59) { assertEquals(1f, c.onBufferedMs(200L), 0.0001f) }
        assertFalse(c.active)
    }

    @Test
    fun a_sustained_dip_arms_once_the_window_is_full() {
        val c = LiveBufferCushion()
        repeat(59) { assertEquals(1f, c.onBufferedMs(5_000L), 0.0001f) }
        // 60th sample completes the window; its own low value is the trigger.
        assertEquals(0.96f, c.onBufferedMs(1_000L), 0.0001f)
        assertTrue(c.active)
    }

    @Test
    fun slowing_banks_reserve_until_the_low_water_mark_recovers() {
        val c = LiveBufferCushion()
        repeat(59) { c.onBufferedMs(2_000L) }
        assertEquals(0.96f, c.onBufferedMs(1_400L), 0.0001f)
        assertEquals(0.96f, c.onBufferedMs(1_400L), 0.0001f) // stays slow while dips remain in the window
        // While slowed the reserve grows; a full window at/above 3 s restores full speed.
        repeat(60) { c.onBufferedMs(3_000L) }
        assertEquals(1f, c.onBufferedMs(3_000L), 0.0001f)
        assertFalse(c.active)
    }

    @Test
    fun hysteresis_between_low_and_restored_keeps_speed_stable() {
        val c = LiveBufferCushion()
        repeat(60) { c.onBufferedMs(1_000L) }
        assertTrue(c.active)
        // Between 1.5 s and 3 s: still slowing (only >= 3 s restores).
        assertEquals(0.96f, c.onBufferedMs(2_000L), 0.0001f)
        assertEquals(0.96f, c.onBufferedMs(1_600L), 0.0001f)
    }
}
