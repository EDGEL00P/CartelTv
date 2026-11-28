package com.carteltv.tv.engine.playback

/**
 * Real network throughput from cumulative byte counts, not the bandwidth meter's estimate
 * (which starts seeded at 20 Mbps and lags on live streams that declare no bitrate).
 * Pure: callers feed totalBytesLoaded from analytics events and a monotonic clock.
 *
 * Samples closer than [windowMs] are ignored; the diff is returned in bits/s.
 */
class ThroughputSampler(
    private val windowMs: Long = 15_000L,
) {
    private var lastAt = -1L
    private var lastBytes = 0L

    /** Returns measured bits/s once the window has elapsed, or null if it is too soon. */
    fun sample(totalBytesLoaded: Long, nowMs: Long): Long? {
        if (lastAt < 0L) {
            lastAt = nowMs
            lastBytes = totalBytesLoaded
            return null
        }
        val dt = nowMs - lastAt
        if (dt < windowMs) return null
        val bytes = totalBytesLoaded - lastBytes
        lastAt = nowMs
        lastBytes = totalBytesLoaded
        if (dt <= 0L || bytes <= 0L) return 0L
        return bytes * 8_000L / dt
    }
}
