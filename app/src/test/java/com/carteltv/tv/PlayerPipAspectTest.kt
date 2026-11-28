package com.carteltv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPipAspectTest {
    @Test
    fun no_usable_size_is_null() {
        assertNull(pipAspect(0, 0, 0))
        assertNull(pipAspect(1920, 0, 0))
        assertNull(pipAspect(-4, 1080, 0))
    }

    @Test
    fun sizes_reduce_to_lowest_terms() {
        assertEquals(16 to 9, pipAspect(1920, 1080, 0))
        assertEquals(4 to 3, pipAspect(1440, 1080, 0))
    }

    @Test
    fun quarter_turn_rotation_swaps_the_sides() {
        assertEquals(9 to 16, pipAspect(1920, 1080, 90))
        assertEquals(9 to 16, pipAspect(1920, 1080, 270))
        // Upside-down stays as it is; the ratio does not change with 180°.
        assertEquals(16 to 9, pipAspect(1920, 1080, 180))
    }

    @Test
    fun ultra_wide_clamps_to_the_window_android_accepts() {
        assertEquals(239 to 100, pipAspect(3840, 1080, 0))
    }

    @Test
    fun ultra_narrow_clamps_to_the_reciprocal() {
        assertEquals(100 to 239, pipAspect(1080, 3840, 0))
    }
}
