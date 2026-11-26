package com.carteltv.tv.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.engine.stability.SecretVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Scheduled recordings: persisted (SecretVault-encrypted), triggered by exact
 * AlarmManager alarms, reboot-survivable via BOOT_COMPLETED re-arm.
 */
object RecordingScheduler {

    @Serializable
    data class Job(
        val id: String,
        val title: String,
        val url: String,
        val startMs: Long,
        val durationMs: Long = 60_000L,
        val maxBytes: Long = 0L,
        /** "smb://…" recording target; null = device storage. */
        val dest: String? = null,
        /** DVR padding: capture starts this much earlier than startMs. */
        val padBeforeMs: Long = 0L,
        /** DVR padding: capture runs this much past the program end. */
        val padAfterMs: Long = 0L,
    )

    /** Alarm time: program start pulled forward by the before-padding. */
    fun armTime(job: Job): Long = job.startMs - job.padBeforeMs.coerceAtLeast(0L)

    /** Capture length: program length plus both paddings. */
    fun captureDuration(job: Job): Long =
        job.durationMs + job.padBeforeMs.coerceAtLeast(0L) + job.padAfterMs.coerceAtLeast(0L)

    private val json get() = com.carteltv.tv.data.network.HttpSupport.json

    // All ledger read-modify-writes share this lock: schedule (UI), prune (IO catch-up),
    // and TriggerReceiver (main) would otherwise lose one side's update.
    private val ledgerLock = Any()

    fun all(ctx: Context): List<Job> =
        SecretVault.decrypt(AppPreferences.scheduledJobsRaw)?.let { raw ->
            runCatching { json.decodeFromString<List<Job>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

    private fun write(ctx: Context, jobs: List<Job>) {
        // Encrypt may fail (null): skip the write rather than blank the ledger.
        val cipher = SecretVault.encrypt(json.encodeToString(jobs)) ?: return
        AppPreferences.scheduledJobsRaw = cipher
    }

    /** One arming law: exact when permitted, inexact-while-idle otherwise (mirrors schedule()). */
    private fun arm(am: AlarmManager, startMs: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs, pi)   // inexact fallback
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs, pi)
        }
    }

    private fun alarmManager(ctx: Context) =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(ctx: Context, job: Job): PendingIntent {
        val i = Intent(ctx, TriggerReceiver::class.java)
            .setAction("com.carteltv.tv.recording.TRIGGER")
            .putExtra("job", json.encodeToString(job))
        // Id-distinct request code: two jobs hashing alike must not share a
        // PendingIntent (the later arm would silently erase the earlier).
        val requestCode = (job.id.hashCode() * 31 + job.startMs.hashCode())
        return PendingIntent.getBroadcast(
            ctx,
            requestCode,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Suppress("SameParameterValue")
    fun schedule(
        ctx: Context, title: String, url: String, startMs: Long, durationMs: Long,
        maxBytes: Long = 0L, dest: String? = null,
        padBeforeMs: Long = AppPreferences.recPadBeforeMin * 60_000L,
        padAfterMs: Long = AppPreferences.recPadAfterMin * 60_000L,
    ): Job {
        val job = Job(
            id = "sched_${System.currentTimeMillis()}",
            title = title, url = url, startMs = startMs, durationMs = durationMs,
            maxBytes = maxBytes, dest = dest,
            padBeforeMs = padBeforeMs.coerceAtLeast(0L), padAfterMs = padAfterMs.coerceAtLeast(0L),
        )
        synchronized(ledgerLock) { write(ctx, all(ctx).toMutableList().apply { add(job) }) }

        val am = alarmManager(ctx)
        arm(am, armTime(job), pi(ctx, job))
        return job
    }

    fun cancel(ctx: Context, jobId: String): Boolean = synchronized(ledgerLock) {
        val jobs = all(ctx)
        val victim = jobs.firstOrNull { it.id == jobId } ?: return false
        alarmManager(ctx).cancel(pi(ctx, victim))
        write(ctx, jobs.filterNot { it.id == jobId })
        return true
    }

    /** Due jobs, WITHOUT pruning — the caller must prune only after a start was accepted,
     *  otherwise a refused foreground start (API 31+ cold start) silently loses the recording. */
    fun dueNow(ctx: Context): List<Job> {
        val now = System.currentTimeMillis()
        return all(ctx).filter { armTime(it) <= now }
    }

    /** Drop a fired job from the ledger. Call only after its start was accepted. */
    fun prune(ctx: Context, job: Job) = synchronized(ledgerLock) {
        write(ctx, all(ctx).filterNot { it.id == job.id })
    }

    /** A start was refused: retry shortly instead of dropping or replaying immediately. */
    fun retrySoon(ctx: Context, job: Job, retryMs: Long = 2 * 60_000L) {
        arm(alarmManager(ctx), System.currentTimeMillis() + retryMs, pi(ctx, job))
    }

    /** Re-arms every future job after reboot. Guarded: exact alarms may be revoked (would throw). */
    fun rearmAll(ctx: Context) {
        val am = alarmManager(ctx)
        val now = System.currentTimeMillis()
        all(ctx).filter { armTime(it) > now }.forEach { job ->
            arm(am, armTime(job), pi(ctx, job))
        }
    }

    /** Alarm receiver: kicks the RecordingService at fire time and prunes the job so restarts never replay it. */
    class TriggerReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val jobJson = intent.getStringExtra("job") ?: return
            val job = runCatching { json.decodeFromString<Job>(jobJson) }.getOrNull() ?: return
            val appCtx = ctx.applicationContext
            // Background starts can be refused on Android 12+; retry shortly and keep the job queued if so.
            try {
                RecordingService.start(appCtx, job.url, job.title, captureDuration(job), job.maxBytes, job.dest)
            } catch (_: IllegalStateException) {
                runCatching { retrySoon(appCtx, job) }
                return
            }
            // A start was accepted above, so the prune law is satisfied; prune() keeps the
            // whole read-modify-write under ledgerLock so a concurrent alarm cannot resurrect this job.
            prune(appCtx, job)
        }
    }
}
