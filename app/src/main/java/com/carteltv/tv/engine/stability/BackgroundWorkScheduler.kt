package com.carteltv.tv.engine.stability

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Playback- and D-pad-navigation-aware background scheduler.
 *
 * Uses `THREAD_PRIORITY_BACKGROUND - 1` (nice 9) for `quiet` work so imports yield to UI
 * rendering and video decode without falling into Android's harsh background cgroup (nice 10),
 * which stretches large EPG/catalog jobs up to 10x on constrained TV boxes.
 */
object BackgroundWorkScheduler {
    internal const val NAV_YIELD_WINDOW_NS = 250_000_000L
    internal const val SEEK_QUIET_WINDOW_NS = 2_000_000_000L

    private val playbackCount = MutableStateFlow(0)
    private val _playbackActive = MutableStateFlow(false)
    val playbackActive: StateFlow<Boolean> = _playbackActive.asStateFlow()

    val isPlaybackActive: Boolean get() = _playbackActive.value

    @Volatile private var seekQuietUntilNanos = 0L
    @Volatile private var lastNavigationNanos = Long.MIN_VALUE / 2
    private val navPause = NavigationPause()

    val heavyJobs = MutableStateFlow(0)

    class PlaybackDeferred : RuntimeException("Background work deferred during active playback")

    /** Two low-priority daemon threads for EPG/catalog/alert work nobody is blocking on. */
    val quiet: CoroutineDispatcher by lazy {
        val count = AtomicInteger()
        Executors.newFixedThreadPool(2) { r ->
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND - 1) }
                r.run()
            }, "cartel-quiet-${count.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /** Two background-priority daemon threads for large EPG/index reads. */
    val reads: CoroutineDispatcher by lazy {
        val count = AtomicInteger()
        Executors.newFixedThreadPool(2) { r ->
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }, "cartel-reads-${count.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    fun enterPlayback() {
        playbackCount.update { it + 1 }
        _playbackActive.value = true
    }

    fun exitPlayback() {
        playbackCount.update { (it - 1).coerceAtLeast(0) }
        _playbackActive.value = playbackCount.value > 0
    }

    fun onSeek(nowNanos: Long = System.nanoTime()) {
        seekQuietUntilNanos = nowNanos + SEEK_QUIET_WINDOW_NS
    }

    fun isSeekSettling(nowNanos: Long = System.nanoTime()): Boolean = nowNanos < seekQuietUntilNanos

    fun onNavigation(nowNanos: Long = System.nanoTime()) {
        lastNavigationNanos = nowNanos
        navPause.onNavigation(nowNanos)
    }

    /** Call between batches on a worker thread (never Main) to yield during active D-pad scroll. */
    fun yieldToNavigation(nowNanos: Long = System.nanoTime()): Boolean {
        val elapsed = nowNanos - lastNavigationNanos
        if (elapsed in 0..NAV_YIELD_WINDOW_NS) {
            Thread.yield()
            return true
        }
        return false
    }

    /** Unwind a synchronous producer loop at a safe checkpoint when playback starts. */
    fun importCheckpoint() {
        if (_playbackActive.value) throw PlaybackDeferred()
    }

    suspend fun <T> heavy(block: suspend () -> T): T {
        heavyJobs.update { it + 1 }
        return try {
            block()
        } finally {
            heavyJobs.update { (it - 1).coerceAtLeast(0) }
        }
    }

    internal fun resetForTest() {
        playbackCount.value = 0
        _playbackActive.value = false
        seekQuietUntilNanos = 0L
        lastNavigationNanos = Long.MIN_VALUE / 2
        heavyJobs.value = 0
        navPause.reset()
    }
}

internal class NavigationPause(
    private val idleWindowNs: Long = 2_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Volatile private var lastNavigation = Long.MIN_VALUE / 2

    fun onNavigation(nowNanos: Long = nanoTime()) {
        lastNavigation = nowNanos
    }

    fun remainingMs(nowNanos: Long = nanoTime()): Long {
        val remaining = idleWindowNs - (nowNanos - lastNavigation)
        return if (remaining <= 0L) 0L else (remaining / 1_000_000L).coerceAtLeast(1L)
    }

    fun reset() {
        lastNavigation = Long.MIN_VALUE / 2
    }
}
