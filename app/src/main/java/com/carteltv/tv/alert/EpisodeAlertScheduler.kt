package com.carteltv.tv.alert

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import com.carteltv.tv.nav.AppLauncher

/**
 * New-episode alert scan (EpisodeAlertScheduler + alarm ladder):
 * a daily exact-alarm scan of the Trakt "my shows" calendar for the coming
 * week; every newly-announced episode (never alerted before) folds into one
 * digest notification. Dedupe ledger + scan hour live in this module's own
 * SharedPreferences (deliberately outside AppPreferences.kt — cold lane), so the
 * triad grows without contesting the hot file.
 */
object EpisodeAlertScheduler {

    const val CHANNEL_ID = "carteltv_episodes"
    private const val PREFS = "carteltv_alerts"
    private const val LEDGER_KEY = "aired_ledger"
    private const val HOUR_KEY = "scan_hour"
    private const val ACTION_SCAN = "com.carteltv.tv.alert.SCAN_EPISODES"
    private const val REQUEST_CODE = 0xE91
    private const val WORK_NAME = "carteltv_episode_scan"

    private val json get() = com.carteltv.tv.data.network.HttpSupport.json

    @Serializable
    data class AiringEpisode(val show: String, val season: Int, val episode: Int, val title: String = "")

    /**
     * Schedules the daily scan as unique periodic work (survives reboot, Doze and process death).
     * Idempotent: an already-queued job keeps its schedule. Also cancels the legacy exact alarm.
     */
    fun armDaily(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        PendingIntent.getBroadcast(
            app, REQUEST_CODE,
            Intent(ACTION_SCAN).setComponent(ComponentName(app, "com.carteltv.tv.alert.EpisodeAlertScheduler\$Fire")),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { am.cancel(it); it.cancel() }
        enqueue(app)
    }

    private fun enqueue(ctx: Context) {
        val hour = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(HOUR_KEY, 10)
        var next = ZonedDateTime.of(LocalDate.now(), LocalTime.of(hour, 0), java.time.ZoneId.systemDefault())
        if (next.toInstant().toEpochMilli() <= System.currentTimeMillis()) next = next.plusDays(1)
        val delay = next.toInstant().toEpochMilli() - System.currentTimeMillis()
        val request = PeriodicWorkRequestBuilder<EpisodeScanWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class EpisodeScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            // A failed scan backs off and retries a few times today instead of waiting a full day.
            if (scanOnce(applicationContext) || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    private const val MAX_ATTEMPTS = 3

    /** Blocking scan. Never throws. False means a transient failure (Trakt unreachable, or an unexpected error) worth retrying. */
    fun scanOnce(ctx: Context): Boolean {
        try {
            val clientId = AppPreferences.traktClientId
            val token = AppPreferences.traktToken
            if (clientId.isBlank() || token.isBlank()) return true

            val today = LocalDate.now()
            val start = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val raw = runCatching {
                com.carteltv.tv.data.trakt.TraktClient(clientId, token, AppPreferences.traktClientSecret)
                    .getBlocking("/calendars/my/shows/$start/7")
            }.getOrNull() ?: return false

            val episodes = runCatching {
                json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                    val o = el.jsonObject
                    val show = o["show"]?.jsonObject ?: return@mapNotNull null
                    val ep = o["episode"]?.jsonObject ?: return@mapNotNull null
                    AiringEpisode(
                        show = show["title"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        season = ep["season"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null,
                        episode = ep["number"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null,
                        title = ep["title"]?.jsonPrimitive?.content ?: "",
                    )
                }
            }.getOrDefault(emptyList())

            if (episodes.isEmpty()) return true

            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ledger = sp.getStringSet(LEDGER_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
            val fresh = episodes.groupBy { it.show }.map { (show, eps) ->
                show to eps.filter { ledger.add("${it.show}:${it.season}:${it.episode}") }
            }.filter { it.second.isNotEmpty() }

            if (fresh.isNotEmpty()) {
                notifyDigest(ctx, fresh.take(4))   // top shows; ledger grows anyway
                sp.edit().putStringSet(LEDGER_KEY, ledger).apply()
            }
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    private fun notifyDigest(ctx: Context, byShow: List<Pair<String, List<AiringEpisode>>>) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val total = byShow.sumOf { it.second.size }
        val text = byShow.joinToString(" · ") { (show, eps) ->
            "$show " + if (eps.size > 1) "(×${eps.size})" else "S${eps[0].season}E${eps[0].episode}"
        }
        val tap = PendingIntent.getActivity(
            ctx, REQUEST_CODE,
            com.carteltv.tv.nav.AppLauncher.mainIntent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            0xE92,
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle("📺 $total new episode${if (total != 1) "s" else ""} this week")
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }
}
