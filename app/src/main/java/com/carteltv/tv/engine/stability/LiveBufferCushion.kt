package com.carteltv.tv.engine.stability

/**
 * Gradual live cushion: instead of a deep start reserve on real-time-speed IPTV servers
 * (which burst 3-9 s then go silent), start fast and watch the buffered low-water mark.
 * When the minimum over the last [window] samples drops under [lowMs], play at
 * [slowFactor] speed (pitch preserved) to bank ~2.4 s of reserve per minute;
 * restore 1.0 once the low-water mark stays at or above [restoredMs].
 *
 * Pure so it can be unit-tested without a player; the caller samples
 * Player.getTotalBufferedDuration() about once a second. Hysteresis on the ring
 * minimum keeps this from flapping while the buffer oscillates around one threshold.
 */
class LiveBufferCushion(
    private val lowMs: Long = 1_500L,
    private val restoredMs: Long = 3_000L,
    private val slowFactor: Float = 0.96f,
    private val window: Int = 60,
) {
    private val ring = LongArray(window)
    private var filled = 0
    private var idx = 0

    /** True while playback is being slowed. */
    var active = false
        private set

    /** Feed one buffered-duration sample; returns the speed multiplier to apply now (0.96f or 1f). */
    fun onBufferedMs(totalBufferedMs: Long): Float {
        ring[idx] = totalBufferedMs
        idx = (idx + 1) % window
        if (filled < window) filled++
        var min = ring[0]
        for (i in 1 until filled) if (ring[i] < min) min = ring[i]
        if (!active) {
            // Only arm once a full window exists: startup fill starts near zero and
            // would otherwise arm the cushion on its own ramp-up.
            if (filled >= window && min < lowMs) active = true
        } else if (min >= restoredMs) {
            active = false
        }
        return if (active) slowFactor else 1f
    }
}
