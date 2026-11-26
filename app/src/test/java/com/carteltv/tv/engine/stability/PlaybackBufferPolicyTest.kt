package com.carteltv.tv.engine.stability

import com.carteltv.tv.data.preferences.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test
    fun live_minReserve_keeps_zapping_fast() {
        val s = PlaybackBufferPolicy.live(AppPreferences.BufferPreset.LOW, lowRam = false)
        assertEquals(5_000, s.minMs)
        assertEquals(15_000, s.maxMs)
        assertEquals(1_000, s.startMs)
        assertEquals(3_000, s.rebufferStartMs)
        assertNull(s.targetBytes)
    }

    @Test
    fun live_std_and_xl_scale() {
        assertEquals(8_000, PlaybackBufferPolicy.live(AppPreferences.BufferPreset.STD, lowRam = false).minMs)
        assertEquals(30_000, PlaybackBufferPolicy.live(AppPreferences.BufferPreset.STD, lowRam = false).maxMs)
        assertEquals(15_000, PlaybackBufferPolicy.live(AppPreferences.BufferPreset.XL, lowRam = false).minMs)
        assertEquals(60_000, PlaybackBufferPolicy.live(AppPreferences.BufferPreset.XL, lowRam = false).maxMs)
    }

    @Test
    fun vod_uses_preset_with_start_reserves() {
        val s = PlaybackBufferPolicy.vod(AppPreferences.BufferPreset.STD, lowRam = false)
        assertEquals(AppPreferences.BufferPreset.STD.minMs, s.minMs)
        assertEquals(AppPreferences.BufferPreset.STD.maxMs, s.maxMs)
        assertEquals(2_500, s.startMs)
        assertEquals(5_000, s.rebufferStartMs)
        assertNull(s.targetBytes)
    }

    @Test
    fun low_ram_caps_the_byte_target() {
        assertEquals(PlaybackBufferPolicy.LOW_RAM_TARGET_BYTES, PlaybackBufferPolicy.live(AppPreferences.BufferPreset.STD, lowRam = true).targetBytes)
        assertEquals(PlaybackBufferPolicy.LOW_RAM_TARGET_BYTES, PlaybackBufferPolicy.vod(AppPreferences.BufferPreset.STD, lowRam = true).targetBytes)
    }

    @Test
    fun live_target_offset_is_about_a_third_of_each_preset_depth() {
        assertEquals(6_000L, PlaybackBufferPolicy.liveTargetOffsetMs(AppPreferences.BufferPreset.LOW))
        assertEquals(10_000L, PlaybackBufferPolicy.liveTargetOffsetMs(AppPreferences.BufferPreset.STD))
        assertEquals(18_000L, PlaybackBufferPolicy.liveTargetOffsetMs(AppPreferences.BufferPreset.XL))
    }
}
