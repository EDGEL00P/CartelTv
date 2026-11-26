package com.carteltv.tv.engine.stability

import com.carteltv.tv.data.preferences.AppPreferences

/**
 * Buffer sizes for the player LoadControl, as a pure spec so the numbers can be unit-tested.
 * Values mirror what PlayerScreen used inline before; change them here, not there.
 *
 * [targetBytes] is the media buffer byte cap. Media3's default sits far above the
 * 40-60 MB media buffer budget Android gives 1 GB TVs, so low-RAM boxes get an explicit cap.
 */
object PlaybackBufferPolicy {
    data class Spec(
        val minMs: Int,
        val maxMs: Int,
        val startMs: Int,
        val rebufferStartMs: Int,
        val targetBytes: Int?,
    )

    const val LOW_RAM_TARGET_BYTES = 48 * 1024 * 1024

    /** Live edge: a server only sends in real time, so deep VOD buffers never fill and only delay zapping. */
    fun live(preset: AppPreferences.BufferPreset, lowRam: Boolean): Spec {
        val (min, max) = when (preset) {
            AppPreferences.BufferPreset.LOW -> 5_000 to 15_000
            AppPreferences.BufferPreset.STD -> 8_000 to 30_000
            AppPreferences.BufferPreset.XL -> 15_000 to 60_000
        }
        return Spec(min, max, startMs = 1_000, rebufferStartMs = 3_000,
            targetBytes = LOW_RAM_TARGET_BYTES.takeIf { lowRam })
    }

    fun vod(preset: AppPreferences.BufferPreset, lowRam: Boolean): Spec = Spec(
        preset.minMs, preset.maxMs, startMs = 2_500, rebufferStartMs = 5_000,
        targetBytes = LOW_RAM_TARGET_BYTES.takeIf { lowRam },
    )

    /**
     * How far behind the live edge playback starts and returns to after a stall, in milliseconds:
     * riding the back of the window is the cushion itself, banked at once instead of built up
     * through LiveBufferCushion's slow 0.96x phase. About a third of each preset's buffer depth —
     * deeper presets sit further from the edge because their streams survive longer silences.
     */
    fun liveTargetOffsetMs(preset: AppPreferences.BufferPreset): Long = when (preset) {
        AppPreferences.BufferPreset.LOW -> 6_000L
        AppPreferences.BufferPreset.STD -> 10_000L
        AppPreferences.BufferPreset.XL -> 18_000L
    }
}
