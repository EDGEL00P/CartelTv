package com.carteltv.tv.alert

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import com.carteltv.tv.PlayerHostActivity
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.data.xtream.GuideModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.carteltv.tv.nav.AppLauncher

/**
 * The viewer's crew (followed teams) and start alerts for the Sports screen. Teams are followed per league; an alert is an
 * exact alarm that fires [LEAD_MS] before kickoff and survives reboot via [rearmAll].
 */
object KickoffAlertScheduler {

    const val CHANNEL_START_ALERTS = "carteltv_start_alerts"

    /** The shared menu wording for both alert entry points (the game sheet and the live guide), so the two never drift apart. */
    const val ARM_TEXT = "Alert me at the start"
    const val CANCEL_TEXT = "Cancel start alert"

    private const val LEAD_MS = 5 * 60_000L
    private const val AUTO_ARM_HORIZON_MS = 48 * 3600_000L
    private const val KEY_CREW = "crew"
    private const val KEY_ALERTS = "start_alerts"

    @Serializable
    data class StartAlert(
        val feed: String,
        val game: String,
        val startMs: Long,
        val network: String? = null,
        /** Channel chosen for this game; lets the alert open the stream directly. */
        val url: String? = null,
        val channelName: String? = null,
    ) {
        val id: String get() = "$feed|$game"
    }

    /** Bumps on every change so Compose can refresh stars and buttons. */
    @Volatile var version: Int = 0

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("carteltv_alerts", Context.MODE_PRIVATE)

    fun teams(ctx: Context): Set<String> = sp(ctx).getStringSet(KEY_CREW, emptySet()).orEmpty()

    private fun teamKey(feed: String, team: String) = "$feed|$team"

    fun inCrew(ctx: Context, feed: String, team: String?): Boolean =
        team != null && teamKey(feed, team) in teams(ctx)

    fun toggleCrew(ctx: Context, feed: String, team: String) {
        val cur = teams(ctx).toMutableSet()
        val k = teamKey(feed, team)
        if (!cur.remove(k)) cur.add(k)
        sp(ctx).edit().putStringSet(KEY_CREW, cur).apply()
        version++
    }

    fun involvesCrew(ctx: Context, feed: String, g: EspnScoreboardClient.Score): Boolean =
        inCrew(ctx, feed, g.nameA) || inCrew(ctx, feed, g.nameB)

    fun alerts(ctx: Context): List<StartAlert> =
        sp(ctx).getString(KEY_ALERTS, null)?.let { raw ->
            runCatching { HttpSupport.json.decodeFromString<List<StartAlert>>(raw) }.getOrNull()
        }.orEmpty()

    private fun write(ctx: Context, list: List<StartAlert>) {
        sp(ctx).edit().putString(KEY_ALERTS, HttpSupport.json.encodeToString(list)).apply()
        version++
    }

    fun hasAlert(ctx: Context, feed: String, game: String): Boolean =
        alerts(ctx).any { it.id == "$feed|$game" }

    private fun alarmManager(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(ctx: Context, r: StartAlert): PendingIntent {
        val i = Intent(ctx, StartAlertReceiver::class.java)
            .setAction("com.carteltv.tv.alert.START_ALERT")
            .putExtra("alert", HttpSupport.json.encodeToString(r))
        return PendingIntent.getBroadcast(
            ctx, r.id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun fireAt(r: StartAlert): Long = r.startMs - LEAD_MS

    private fun arm(ctx: Context, r: StartAlert) {
        val am = alarmManager(ctx)
        val at = fireAt(r)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r))
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r))
        }
    }

    /** Sets a start alert for [g]. Returns false when kickoff is already too close or past. */
    fun armAlert(ctx: Context, feed: String, g: EspnScoreboardClient.Score, url: String? = null, channelName: String? = null): Boolean =
        armAlert(ctx, feed, g.name, g.startMs, g.network, url, channelName)

    /** Sets a start alert for any programme: a game, a show or a film on a live channel. */
    fun armAlert(
        ctx: Context, feed: String, title: String, startMs: Long,
        network: String? = null, url: String? = null, channelName: String? = null,
    ): Boolean {
        if (startMs <= System.currentTimeMillis() + 30_000L) return false
        val r = StartAlert(feed, title, startMs, network, url, channelName)
        val rest = alerts(ctx).filterNot { it.id == r.id }
        write(ctx, rest + r)
        // A kickoff inside the lead window still gets a prompt alert instead of a past-due alarm.
        arm(ctx, if (fireAt(r) <= System.currentTimeMillis()) r.copy(startMs = System.currentTimeMillis() + LEAD_MS + 5_000L) else r)
        return true
    }

    fun cancelAlert(ctx: Context, feed: String, game: String) {
        val cur = alerts(ctx)
        val victim = cur.firstOrNull { it.id == "$feed|$game" } ?: return
        alarmManager(ctx).cancel(pi(ctx, victim))
        write(ctx, cur - victim)
    }

    fun rearmAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val live = alerts(ctx).filter { it.startMs > now }
        if (live.size != alerts(ctx).size) write(ctx, live)
        live.forEach { runCatching { arm(ctx, it) } }
    }

    /** For every upcoming game of a followed team: arm a start alert and follow it for goal alerts. */
    fun autoArm(ctx: Context, feed: String, games: List<EspnScoreboardClient.Score>) {
        val now = System.currentTimeMillis()
        for (g in games) {
            if (g.state != "pre" || g.startMs <= now || g.startMs - now > AUTO_ARM_HORIZON_MS) continue
            if (!involvesCrew(ctx, feed, g)) continue
            if (!hasAlert(ctx, feed, g.name)) armAlert(ctx, feed, g)
            LiveScoreMonitor.ensureFollowed(ctx, feed, g.name)
        }
    }

    class StartAlertReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val raw = intent.getStringExtra("alert") ?: return
            val r = runCatching { HttpSupport.json.decodeFromString<StartAlert>(raw) }.getOrNull() ?: return
            val app = ctx.applicationContext
            write(app, alerts(app).filterNot { it.id == r.id })

            val nm = app.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_START_ALERTS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_START_ALERTS, "Start alerts", NotificationManager.IMPORTANCE_HIGH),
                )
            }

            val onAir = r.channelName?.let(GuideModel::displayName) ?: r.network
            val watch: Intent? = r.url?.let {
                Intent(app, PlayerHostActivity::class.java)
                    .putExtra("url", it).putExtra("title", onAir ?: r.game).putExtra("refId", "live:sports")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val tapIntent = watch ?: AppLauncher.mainIntent(app, "scores")
            val tap = PendingIntent.getActivity(
                app, r.id.hashCode(), tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val mins = ((r.startMs - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
            nm.notify(
                r.id.hashCode(),
                NotificationCompat.Builder(app, CHANNEL_START_ALERTS)
                    .setSmallIcon(android.R.drawable.ic_menu_today)
                    .setContentTitle("${r.game} starts ${if (mins <= 1) "now" else "in $mins min"}")
                    .setContentText(listOfNotNull(onAir, if (watch != null) "Press to tune in" else null).joinToString(" · "))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .build(),
            )

            // Starting an activity from the background is only honoured while the app is in the foreground,
            // so this is best effort; the notification above is the dependable path.
            if (watch != null && AppPreferences.sportsAutoPlay) runCatching { app.startActivity(watch) }
        }
    }
}
