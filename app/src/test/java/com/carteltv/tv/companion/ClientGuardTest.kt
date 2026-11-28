package com.carteltv.tv.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ClientGuardTest {

    private var t = 0L

    private fun guard(
        events: Int = ClientGuardTestDefaults.EVENTS,
        windowMs: Long = ClientGuardTestDefaults.WINDOW_MS,
    ) = ClientGuard(events, windowMs, ClientGuardTestDefaults.FAILS, ClientGuardTestDefaults.LOCKOUT_MS) { t }

    private object ClientGuardTestDefaults {
        const val EVENTS = 3
        const val WINDOW_MS = 1_000L
        const val FAILS = 2
        const val LOCKOUT_MS = 5_000L
    }

    @Test
    fun allows_up_to_the_limit_then_refuses() {
        val g = guard()
        assertTrue(g.allow("phone"))
        assertTrue(g.allow("phone"))
        assertTrue(g.allow("phone"))
        assertFalse(g.allow("phone"))
    }

    @Test
    fun the_window_resets_so_a_slow_client_is_never_refused() {
        val g = guard()
        repeat(3) { assertTrue(g.allow("phone")) }
        t += 1_001
        assertTrue(g.allow("phone"))
    }

    @Test
    fun clients_are_independent() {
        val g = guard()
        repeat(3) { g.allow("a") }
        assertFalse(g.allow("a"))
        assertTrue(g.allow("b"))
    }

    @Test
    fun bad_tokens_lock_the_client_out_after_the_limit() {
        val g = guard()
        g.fail("a")
        assertTrue(g.allow("a"))
        g.fail("a")
        assertFalse(g.allow("a"))
    }

    @Test
    fun the_lockout_lifts_after_its_time() {
        val g = guard()
        repeat(2) { g.fail("a") }
        t += 5_001
        assertTrue(g.allow("a"))
    }

    @Test
    fun old_entries_are_swept_so_the_map_cannot_grow() {
        val g = guard()
        repeat(500) { i -> g.allow("c$i"); t += 10_000 }
        // Nothing observable beyond not blowing up; the sweep is exercised on every call.
        assertTrue(g.allow("c499"))
    }

    /**
     * The companion server serves several requests at once. With the clock frozen inside one
     * window, exactly [ClientGuardTestDefaults.EVENTS] callers may pass no matter how many
     * workers race the guard: unsynchronized ++count loses updates and lets extra requests
     * through, so this pins the lock as well as the limit.
     */
    @Test
    fun parallel_workers_cannot_exceed_the_window_limit() {
        val g = guard()
        val racers = 100
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(racers)
        val allowed = AtomicInteger(0)
        repeat(racers) {
            Thread {
                startGate.await()
                if (g.allow("phone")) allowed.incrementAndGet()
                done.countDown()
            }.start()
        }
        startGate.countDown()
        done.await()
        assertEquals(ClientGuardTestDefaults.EVENTS, allowed.get())
    }
}
