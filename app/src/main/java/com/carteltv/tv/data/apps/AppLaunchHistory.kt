package com.carteltv.tv.data.apps

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * No-ADB recent-apps foreground tracker.
 *
 * Records foreground app packages observed by [com.carteltv.tv.data.remap.KeyRemapService] on
 * `TYPE_WINDOW_STATE_CHANGED`, merges Android's [UsageStatsManager] foreground history when
 * `PACKAGE_USAGE_STATS` is granted, enforces a 12-hour TTL, and honors per-package close
 * tombstones so dismissed cards never snap back until re-launched.
 */
object AppLaunchHistory {

    private const val PREF_NAME = "cartel_app_history"
    private const val MAX_ENTRIES = 16
    const val KEEP_MS = 12L * 60 * 60 * 1000

    private val SKIP_PACKAGES = setOf(
        "com.carteltv.tv",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.android.tv.settings",
        "com.android.systemui",
        "com.android.vending",
        "com.android.permissioncontroller",
    )

    private val lock = Any()
    @Volatile private var lastPkg: String? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Pure merge & rank helper for unit tests: keeps entries inside TTL and newer than tombstone. */
    fun mergeAndRank(
        observed: Map<String, Long>,
        tombstones: Map<String, Long>,
        nowMs: Long,
        keepMs: Long = KEEP_MS,
        limit: Int = MAX_ENTRIES,
        excluded: Set<String> = SKIP_PACKAGES,
    ): List<String> =
        observed.entries
            .filter { (pkg, ts) ->
                pkg.isNotBlank() &&
                    pkg !in excluded &&
                    !pkg.startsWith("com.google.android.apps.tv.launcher") &&
                    nowMs - ts <= keepMs &&
                    ts > (tombstones[pkg] ?: 0L)
            }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)

    fun onForeground(ctx: Context, pkg: String, isKnownApp: (String) -> Boolean) {
        if (pkg == lastPkg) return
        lastPkg = pkg
        if (pkg == ctx.packageName || pkg in SKIP_PACKAGES || !isKnownApp(pkg)) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val list = readStored(ctx).filterNot { it.first == pkg || now - it.second > KEEP_MS }
            writeStored(ctx, (listOf(pkg to now) + list).take(MAX_ENTRIES))
        }
    }

    fun forget(ctx: Context, pkg: String) {
        synchronized(lock) {
            writeStored(ctx, readStored(ctx).filterNot { it.first == pkg })
            sp(ctx).edit().putLong("gone:$pkg", System.currentTimeMillis()).apply()
            if (lastPkg == pkg) lastPkg = null
        }
    }

    fun closeApp(ctx: Context, pkg: String) {
        forget(ctx, pkg)
        runCatching {
            ctx.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(pkg)
        }
    }

    fun recentPackages(ctx: Context, limit: Int = 12): List<String> {
        val now = System.currentTimeMillis()
        val seen = HashMap<String, Long>()
        synchronized(lock) {
            readStored(ctx).forEach { (p, t) ->
                if (now - t <= KEEP_MS) seen[p] = maxOf(seen[p] ?: 0L, t)
            }
        }
        if (hasUsageAccess(ctx)) {
            runCatching {
                val um = ctx.getSystemService(UsageStatsManager::class.java)
                val events = um?.queryEvents(now - KEEP_MS, now)
                val ev = UsageEvents.Event()
                while (events != null && events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    @Suppress("DEPRECATION")
                    if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        seen[ev.packageName] = maxOf(seen[ev.packageName] ?: 0L, ev.timeStamp)
                    }
                }
            }
        }
        val prefs = sp(ctx)
        val tombstones = seen.keys.associateWith { prefs.getLong("gone:$it", 0L) }
        return mergeAndRank(seen, tombstones, now, KEEP_MS, limit)
    }

    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun readStored(ctx: Context): List<Pair<String, Long>> =
        sp(ctx).getString("list", "").orEmpty()
            .split('\n')
            .mapNotNull { line ->
                val p = line.substringBefore(' ')
                val t = line.substringAfter(' ', "").toLongOrNull()
                if (p.isBlank() || t == null) null else p to t
            }

    private fun writeStored(ctx: Context, list: List<Pair<String, Long>>) {
        sp(ctx).edit()
            .putString("list", list.joinToString("\n") { "${it.first} ${it.second}" })
            .apply()
    }
}
