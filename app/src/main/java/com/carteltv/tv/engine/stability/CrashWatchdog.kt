package com.carteltv.tv.engine.stability

import android.content.Context
import android.os.Process
import java.io.File

/**
 * Crash-frequency watchdog (early-warning tripwire before ANR/crash storms):
 * a file of recent crash timestamps; `tooMany` decides safe-mode.
 * Written BEFORE a crash via default handler chaining; read at boot.
 */
object CrashWatchdog {

    private const val FILE = "crash_log.txt"
    private const val TRACE_FILE = "last-crash.txt"
    private const val WINDOW_MS = 10 * 60_000L      // 10 min window
    private const val THRESHOLD = 3                 // 3 crashes = unhealthy
    private const val MAX_ENTRIES = 40
    private const val MAX_TRACE_CHARS = 4_000

    @Volatile var lastCrashAt: Long = 0; private set

    fun install(appContext: Context) {
        val ctx = appContext.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(ctx, System.currentTimeMillis()) }
            runCatching { saveTrace(ctx, throwable) }
            lastCrashAt = System.currentTimeMillis()
            previous?.uncaughtException(thread, throwable) ?: Process.killProcess(Process.myPid())
        }
    }

    private fun saveTrace(ctx: Context, throwable: Throwable) {
        val trace = throwable.stackTraceToString().take(MAX_TRACE_CHARS)
        File(ctx.filesDir, TRACE_FILE).writeText(trace)
    }

    fun lastCrashTrace(ctx: Context): String =
        runCatching { File(ctx.filesDir, TRACE_FILE).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")

    fun record(ctx: Context, atMs: Long) {
        val f = File(ctx.filesDir, FILE)
        val stamps = readStamps(f) + atMs
        f.writeText(stamps.takeLast(MAX_ENTRIES).joinToString(","))
    }

    fun recentCount(ctx: Context, windowMs: Long = WINDOW_MS): Int {
        val now = System.currentTimeMillis()
        return readStamps(File(ctx.filesDir, FILE)).count { now - it <= windowMs }
    }

    /** True when recent crashes crossed the threshold — Safe Mode cue. */
    fun tooMany(ctx: Context): Boolean = recentCount(ctx) >= THRESHOLD

    /** Called after successful stable uptime to forgive past crashes. */
    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }

    fun crashes(ctx: Context): List<Long> = readStamps(File(ctx.filesDir, FILE))

    private fun readStamps(f: File): List<Long> =
        runCatching {
            f.takeIf { it.exists() }?.readText()?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
        }.getOrDefault(emptyList())
}
