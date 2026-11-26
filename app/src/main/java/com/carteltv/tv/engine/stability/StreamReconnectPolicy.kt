package com.carteltv.tv.engine.stability

/**
 * Backoff for re-opening a live stream after an error or stall: 1s doubling to a 30s cap,
 * giving up after [maxAttempts]. Pure so it can be unit-tested without a player.
 *
 * [jitter] spreads each delay by ±that fraction (0 = exact) so many clients dropped by the same
 * outage do not all reconnect on the same tick; [random] returns a value in [0, 1).
 */
class StreamReconnectPolicy(
    private val maxAttempts: Int = 6,
    private val baseMs: Long = 1_000L,
    private val capMs: Long = 30_000L,
    private val jitter: Double = 0.0,
    private val random: () -> Double = Math::random,
) {
    private var attempt = 0

    /** Delay before the next retry, or null when the budget is spent. */
    fun nextDelayMs(): Long? {
        if (attempt >= maxAttempts) return null
        val d = (baseMs shl attempt).coerceAtMost(capMs)
        attempt++
        if (jitter <= 0.0) return d
        return (d * (1.0 - jitter + 2.0 * jitter * random())).toLong().coerceAtLeast(0L)
    }

    fun reset() { attempt = 0 }
}
