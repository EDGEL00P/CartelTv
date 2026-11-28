package com.carteltv.tv.alert
import com.carteltv.tv.data.espn.LeagueCatalog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.carteltv.tv.data.espn.EspnScoreboardClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.system.ImmersiveModeController

/**
 * Sports goal monitor (poll-diff service):
 * a single-instance foreground monitor that follows games picked on the
 * Scores screen and pushes score-change notifications while the app is NOT
 * on screen ("alerts suspended while scores are visible" coherence, honored
 * here by suppressing alerts while foreground instead of refusing to start —
 * steadier than a refuse-and-retire approach).
 *
 * State telemetry uses these terminal states:
 * not_started / running / user_stop / paused_foreground / os_timeout / final.
 *
 * All state lives in THIS module's own prefs (carteltv_alerts) — AppPreferences.kt is
 * deliberately untouched. Zero new dependencies: ESPN public board via the
 * existing EspnScoreboardClient, AlarmManager law via an exact-armed none (monitor is
 * service-poll based, boot re-arm only re-arms the SERVICE when follows exist).
 */
object LiveScoreMonitor {

    const val STATE_FINAL = "final"
    const val STATE_USER_STOP = "user_stop"
    const val STATE_RUNNING = "running"
    const val STATE_CAP = "os_timeout"

    private const val KEY_FOLLOWS = "followed_games"   // "feed|gameName|ts" triples
    private const val POLL_MS = 45_000L
    private const val MAX_RUN_MS = 3 * 60 * 60 * 1000L  // hard cap: self-extinguish
    private const val STATUS_NOTIF_ID = 0x6341
    const val ACTION_STOP = "com.carteltv.tv.alert.GOAL_TRACK_STOP"
    const val CHANNEL_STATUS = "carteltv_goal_tracking"
    const val CHANNEL_ALERTS = "carteltv_goal_alerts"

    /** Version bumps on every follow/unfollow — compose reads this to refresh badges. */
    @Volatile var followingVersion: Int = 0

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("carteltv_alerts", Context.MODE_PRIVATE)

    /** Current follows as (feed, gameName). */
    fun follows(ctx: Context): List<Pair<String, String>> =
        sp(ctx).getStringSet(KEY_FOLLOWS, emptySet()).orEmpty()
            .mapNotNull { row ->
                val p = row.split('|')
                if (p.size >= 2) p[0] to p[1] else null
            }

    fun isFollowed(ctx: Context, feed: String, game: String): Boolean =
        follows(ctx).any { it.first == feed && it.second == game }

    /** Follow + kick the monitor. Unfollow + stop-if-empty. */
    fun toggle(ctx: Context, feed: String, game: String) {
        val key = "$feed|$game|${System.currentTimeMillis()}"
        val cur = sp(ctx).getStringSet(KEY_FOLLOWS, emptySet()).orEmpty().toMutableSet()
        val existing = cur.firstOrNull { it.startsWith("$feed|$game|") }
        if (existing != null) cur.remove(existing) else cur.add(key)
        sp(ctx).edit().putStringSet(KEY_FOLLOWS, cur).apply()
        followingVersion++

        if (cur.isEmpty()) {
            // Unfollow from a background context can throw on API 26+; the ScoreMonitorService
            // stops itself after its loop sees the empty ledger anyway.
            runCatching { ctx.startService(Intent(ctx, ScoreMonitorService::class.java).setAction(ACTION_STOP)) }
        } else {
            // Guarded like the stop path above: a background-context FGS start can throw.
            val i = Intent(ctx, ScoreMonitorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }
    }

    /** Follows a game if it isn't followed yet; never unfollows. */
    fun ensureFollowed(ctx: Context, feed: String, game: String) {
        if (!isFollowed(ctx, feed, game)) toggle(ctx, feed, game)
    }

    /** Drop follows whose game vanished from every followed feed's board for 24 h+. */
    private fun pruneDead(ctx: Context, boards: Map<String, List<EspnScoreboardClient.Score>>) {
        val rows = sp(ctx).getStringSet(KEY_FOLLOWS, emptySet()).orEmpty()
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        val kept = rows.filter { row ->
            val p = row.split('|')
            // A feed whose fetch failed this cycle proves nothing, so its follows are never pruned.
            val board = boards[p.firstOrNull()] ?: return@filter true
            !(p.size >= 3 && p[2].toLongOrNull() ?: 0L < now - 24 * 3600_000L && board.none { it.name == p.getOrNull(1) })
        }
        if (kept.size != rows.size) {
            sp(ctx).edit().putStringSet(KEY_FOLLOWS, kept.toSet()).apply()
            followingVersion++
        }
    }

    /** Sport-flavored exclamation — small delight, matches genre. */
    private fun shout(feed: String): String = when {
        feed.contains("football", true) -> "TOUCHDOWN"
        feed.contains("basketball", true) -> "BUCKET"
        feed.contains("baseball", true) -> "RUN SCORED"
        else -> "GOAL"
    }

    private fun scoreLine(s: EspnScoreboardClient.Score): String = "${s.abbrB ?: "?"} ${s.scoreB ?: "-"} — ${s.scoreA ?: "-"} ${s.abbrA ?: "?"}"

    /** ⚽🔌 The poll-host service. Single instance. */
    class ScoreMonitorService : Service() {

        companion object {
            /** True from the first start command until the service is destroyed. */
            @Volatile var alive = false
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var wake: PowerManager.WakeLock? = null
        private lateinit var nm: NotificationManager
        /** Guards the single poll loop: a re-start supersedes, never doubles. */
        private var loopJob: kotlinx.coroutines.Job? = null

        private fun statusNotification(nGames: Int, note: String): Notification {
            val stopPi = PendingIntent.getService(
                this, 0,
                Intent(this, ScoreMonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val tap = PendingIntent.getActivity(
                this, 0,
                com.carteltv.tv.nav.AppLauncher.mainIntent(this, "scores"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("🏆 Tracking $nGames game${if (nGames != 1) "s" else ""}")
                .setContentText(note)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP TRACKING", stopPi)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(tap)
                .build()
        }

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            alive = true
            nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CHANNEL_STATUS) == null)
                    nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Game tracking", NotificationManager.IMPORTANCE_LOW))
                if (nm.getNotificationChannel(CHANNEL_ALERTS) == null)
                    nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Score alerts", NotificationManager.IMPORTANCE_DEFAULT))
            }

            // Law: ALWAYS promote to foreground first — even a start that turns
            // out to be a stop (empty follows) owes the system its foreground
            // promotion inside the FGS grace window.
            startForeground(STATUS_NOTIF_ID, statusNotification(0, "Checking the board…"))

            if (intent?.action == ACTION_STOP) {
                scope.launch { runCatching { finish(STATE_USER_STOP) } }
                return START_NOT_STICKY
            }

            val followsAll = follows(this)
            if (followsAll.isEmpty()) { runCatching { finish(STATE_USER_STOP) }; return START_NOT_STICKY }

            wake?.let { runCatching { it.release() } }
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CartelTV:goal-monitor")
                .apply { setReferenceCounted(false); acquire(MAX_RUN_MS) }

            // Single-loop law: cancel any predecessor (its dicts die with it),
            // then start the one true loop. Re-follow mid-track ≠ second poller.
            loopJob?.cancel()
            loopJob = scope.launch { loop() }
            return START_NOT_STICKY
        }

        private suspend fun loop() {
            val started = System.currentTimeMillis()
            val espn: com.carteltv.tv.data.espn.ScoreboardSource = com.carteltv.tv.data.espn.CompositeScoreboardSource.default
            val last = HashMap<String, EspnScoreboardClient.Score>()       // "feed|game" → last seen
            val lastPing = HashMap<String, Long>()               // alert cooldowns
            val alerted = HashMap<String, EspnScoreboardClient.Score>()    // baseline each alert is compared with
            try {
                while (scope.isActive && !Thread.currentThread().isInterrupted) {
                    val ctx = this@ScoreMonitorService
                    val followsNow = follows(ctx)
                    if (followsNow.isEmpty()) { finish(STATE_USER_STOP); return }
                    if (System.currentTimeMillis() - started > MAX_RUN_MS) { finish(STATE_CAP); return }

                    val byFeed = followsNow.groupBy({ it.first }, { it.second })
                    // One fetch per feed per cycle, shared by pruning and alert detection; a failed feed skips this cycle only.
                    val boards = HashMap<String, List<EspnScoreboardClient.Score>>()
                    for (feed in byFeed.keys) {
                        try {
                            boards[feed] = espn.scoreboard(LeagueCatalog.pathFor(feed))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) { // bughunt:ignore kt-empty-catch
                        }
                    }
                    pruneDead(ctx, boards)

                    var anyLive = false
                    for ((feed, games) in byFeed) {
                        val board = boards[feed] ?: continue
                        for (g in games) {
                            val s = board.firstOrNull { it.name == g } ?: continue
                            if (s.state == "in") anyLive = true
                            val k = "$feed|$g"
                            last[k] = s
                            // Compare with the last snapshot that was alerted on or skipped as clock-only, so a change
                            // that lands inside the cooldown is delayed to the next poll, not lost.
                            val base = alerted[k]
                            if (base == null) alerted[k] = s
                            if (base != null && s != base) {
                                val headline = when (com.carteltv.tv.data.espn.ScoreDifferential.diff(base, s)) {
                                    com.carteltv.tv.data.espn.ScoreDifferential.Event.KICKOFF -> "🏈 KICKOFF · ${s.name}"
                                    com.carteltv.tv.data.espn.ScoreDifferential.Event.FINAL -> "🏁 FINAL · ${s.name} · ${scoreLine(s)}"
                                    com.carteltv.tv.data.espn.ScoreDifferential.Event.SCORE -> "${shout(feed)}! · ${s.name}"
                                    null -> null
                                }
                                val nowMs = System.currentTimeMillis()
                                if (headline == null) {
                                    // Clock or detail changed, nothing to say: move the baseline and keep the cooldown unspent.
                                    alerted[k] = s
                                } else if (nowMs - (lastPing[k] ?: 0) > 20_000) {
                                    lastPing[k] = nowMs
                                    alerted[k] = s
                                    // App on screen or scores hidden = alerts silenced
                                    if (!com.carteltv.tv.system.ImmersiveModeController.anyVisible &&
                                        !com.carteltv.tv.data.preferences.AppPreferences.spoilerShield
                                    ) {
                                        val text = scoreLine(s) + if (s.detail.isNotBlank()) " · ${s.detail}" else ""
                                        val delayMs = com.carteltv.tv.data.preferences.AppPreferences.goalAlertDelay * 1000L
                                        // The delay runs beside the poll loop so a lagging stream is spoiler-free without slowing updates.
                                        scope.launch {
                                            if (delayMs > 0) delay(delayMs)
                                            nm.notify(k.hashCode(), NotificationCompat.Builder(ctx, CHANNEL_ALERTS)
                                                .setSmallIcon(android.R.drawable.stat_notify_chat)
                                                .setContentTitle(headline)
                                                .setContentText(text)
                                                .setAutoCancel(true)
                                                .build())
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // everyone finished (none live/pre among followed) → done, self-extinguish
                    val statesLeft = byFeed.flatMap { (feed, games) ->
                        // cheap re-check straight from the last snapshot map
                        games.mapNotNull { g -> last["$feed|$g"]?.state }
                    }
                    if (statesLeft.isNotEmpty() && statesLeft.none { it == "in" || it == "pre" }) {
                        nm.notify(STATUS_NOTIF_ID + 1, NotificationCompat.Builder(ctx, CHANNEL_STATUS)
                            .setSmallIcon(android.R.drawable.ic_menu_today)
                            .setContentTitle("🏁 All tracked games finished")
                            .setAutoCancel(true)
                            .build())
                        finish(STATE_FINAL)
                        return
                    }

                    nm.notify(STATUS_NOTIF_ID, statusNotification(followsNow.size, scoreTicker(byFeed, last)))

                    // foreground → silent-walk mode: keep polling cheaply, alerts still armed
                    delay(POLL_MS)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A superseding start cancels this loop; finishing here would tear down the successor's wake lock and service.
                throw e
            } catch (_: Exception) {
                runCatching { finish(STATE_USER_STOP) }
            }
        }

        private fun scoreTicker(
            byFeed: Map<String, List<String>>,
            last: Map<String, EspnScoreboardClient.Score>,
        ): String = byFeed.entries.take(3).joinToString(" · ") { (feed, games) ->
            games.firstNotNullOfOrNull { g -> last["$feed|$g"] }?.let { "${it.abbrB} ${it.scoreB}-${it.scoreA} ${it.abbrA}" } ?: feed
        }

        private fun finish(state: String) {
            wake?.let { runCatching { it.release() } }
            wake = null
            nm.cancel(STATUS_NOTIF_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        // Android 15+ ends a dataSync service after 6 h in 24 h and crashes the app if it does not stop within seconds.
        override fun onTimeout(startId: Int, fgsType: Int) {
            loopJob?.cancel()
            finish(STATE_CAP)
        }

        override fun onDestroy() {
            alive = false
            wake?.let { runCatching { it.release() } }
            wake = null
            scope.cancel()
            super.onDestroy()
        }
    }

    /**
     * Re-arms the foreground monitor after reboot when followed games exist. Apps targeting Android 15 or later
     * cannot start a dataSync service from BOOT_COMPLETED, so there [startIfFollowing] picks it up when the launcher
     * is next on screen.
     */
    fun rearmOnBoot(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 35) return
        startIfFollowing(ctx)
    }

    /** Starts the monitor when games are followed and it is not already running. Call it while the app is visible. */
    fun startIfFollowing(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (ScoreMonitorService.alive || follows(appCtx).isEmpty()) return
        val i = Intent(appCtx, ScoreMonitorService::class.java)
        // Boot re-arm can race FGS start restrictions; failure just means the next visible start picks it up.
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) appCtx.startForegroundService(i) else appCtx.startService(i)
        }
    }
}
