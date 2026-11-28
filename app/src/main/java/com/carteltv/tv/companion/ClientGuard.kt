package com.carteltv.tv.companion

/**
 * Per-client flood control for the LAN companion, JVM-pure so it is unit-testable.
 *
 * Two rules, both per client address, like the phone remote's message rate limit:
 * at most [maxEvents] requests in [windowMs], and [maxFails] bad tokens within a
 * window lock the client out for [lockoutMs]. Entries older than a few windows are
 * dropped so the map cannot grow on a busy LAN.
 *
 * Thread-safe: the companion server serves several requests at once, and a map or
 * counter updated from parallel workers would silently lose updates, defeating
 * both the flood limit and the lockout.
 */
internal class ClientGuard(
    private val maxEvents: Int,
    private val windowMs: Long,
    private val maxFails: Int,
    private val lockoutMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Window(var start: Long) {
        var count = 0
        var fails = 0
        var lockedUntil = 0L
    }

    private val lock = Any()
    private val clients = HashMap<String, Window>()

    /** True when [id] may proceed this event; false while flooding or locked out. */
    fun allow(id: String): Boolean = synchronized(lock) {
        val nowMs = now()
        sweep(nowMs)
        val w = clients.getOrPut(id) { Window(nowMs) }
        if (nowMs < w.lockedUntil) return false
        if (nowMs - w.start >= windowMs) {
            w.start = nowMs
            w.count = 0
        }
        ++w.count <= maxEvents
    }

    /** Counts a rejected token for [id]; at [maxFails] the client is locked out. */
    fun fail(id: String) {
        synchronized(lock) {
            val nowMs = now()
            sweep(nowMs)
            val w = clients.getOrPut(id) { Window(nowMs) }
            if (nowMs - w.start >= windowMs) {
                w.start = nowMs
                w.fails = 0
            }
            if (++w.fails >= maxFails) w.lockedUntil = nowMs + lockoutMs
        }
    }

    /** Caller must hold [lock]. */
    private fun sweep(nowMs: Long) {
        val idle = 5 * windowMs
        clients.entries.removeAll { (nowMs - it.value.start > idle) && (nowMs >= it.value.lockedUntil) }
    }
}
