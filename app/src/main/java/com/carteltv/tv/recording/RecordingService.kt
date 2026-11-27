package com.carteltv.tv.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M7 — Recording foreground service. One capture per start; stopSelf when capture ends.
 * Writes to app-external-files (no storage permission needed). Notification has STOP action.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var capture: HlsCapture? = null
    private lateinit var nm: NotificationManager

    companion object {
        const val CH_ID = "carteltv_recordings"
        const val NOTIF_ID_BASE = 0xC171
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_MAX_BYTES = "max_bytes"
        const val EXTRA_DEST = "dest"   // "smb://..." for SMB target; default device
        const val ACTION_STOP = "com.carteltv.tv.recording.STOP"
        const val STOP_EXTRA_ID = "notif_id"

        fun start(
            ctx: Context,
            url: String,
            title: String,
            durationMs: Long = 60_000L,
            maxBytes: Long = 0L,
            dest: String? = null,
        ) {
            val i = Intent(ctx, RecordingService::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DURATION_MS, durationMs)
                .putExtra(EXTRA_MAX_BYTES, maxBytes)
            dest?.let { i.putExtra(EXTRA_DEST, it) }
            // startForegroundService for reliable background record
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        @Volatile var capturing: Boolean = false
            private set

        fun stopCapture(ctx: Context) {
            val i = Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP)
            // The service may not be running (capture already ended); a refused
            // foreground start throws on API 31+ and must not crash the caller.
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun recordingsDir(ctx: Context): File =
            File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings").apply { mkdirs() }

        fun listRecordings(ctx: Context): List<File> =
            recordingsDir(ctx).listFiles { f -> f.extension == "ts" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FGS law: promote FIRST, decide after — an early return below must
        // never owe the system a startForeground it never gave (remote-
        // startable service ⇒ ForegroundServiceDidNotStartInTime crash).
        nm = getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(
                NotificationChannel(CH_ID, "Recordings", NotificationManager.IMPORTANCE_LOW))
        }
        val notifId = (NOTIF_ID_BASE + startId)
        startForeground(notifId, buildNotification(intent?.getStringExtra(EXTRA_TITLE) ?: "recording", 0L, notifId))

        if (intent?.action == ACTION_STOP) {
            capture?.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL) ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "recording"
        val durMs = intent.getLongExtra(EXTRA_DURATION_MS, 60_000L)
        val maxBytes = intent.getLongExtra(EXTRA_MAX_BYTES, 0L)

        val tsFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val safeName = title.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40)
        val requestedDest = intent.getStringExtra(EXTRA_DEST)
        val sink: CaptureSink = when {
            requestedDest == null -> LocalCaptureSink(File(recordingsDir(this), "${safeName}_${tsFmt.format(Date())}.ts"))
            requestedDest.startsWith("smb://") -> SmbCaptureSink(requestedDest)
            requestedDest.startsWith("content://") ->
                SafCaptureSink(this, requestedDest, "${safeName}_${tsFmt.format(Date())}.ts")
            else -> LocalCaptureSink(File(recordingsDir(this), "${safeName}_${tsFmt.format(Date())}.ts"))
        }

        startForeground(notifId, buildNotification(title, 0L, notifId))

        // One-capture law: a second start supersedes — stop the previous
        // capture first so its STOP button / onDestroy never orphan it.
        capture?.stop()
        capture = HlsCapture(url, sink) { LocalCaptureSink(File(recordingsDir(this), "${safeName}_${tsFmt.format(Date())}.ts")) }
        val c = capture!!
        capturing = true
        scope.launch {
            val end = try { c.run(maxDurationMs = durMs, maxBytes = maxBytes) } finally { if (capture === c) capturing = false }
            // A superseded capture must not demote or stop the service its successor is running in.
            val superseded = capture !== c
            val finalTitle = if (end == CaptureEnd.NetworkFallback || c.fellBack) "$title (on device — network folder unreachable)"
                else title
            kotlin.runCatching {
                nm.notify(notifId + 9000, finalNotification(finalTitle, end, notifId + 9000))
            }
            if (!superseded) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    // Android 15+ ends a dataSync service after 6 h in 24 h and crashes the app if it does not stop within seconds.
    override fun onTimeout(startId: Int, fgsType: Int) {
        capture?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        capture?.stop()
        capturing = false
        scope.cancel()
        super.onDestroy()
    }

    private fun recordingsIntent(notifId: Int): PendingIntent = PendingIntent.getActivity(
        this,
        notifId,
        com.carteltv.tv.nav.AppLauncher.mainIntent(this, "recordings"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(title: String, elapsedMs: Long, notifId: Int): Notification {
        val stopIntent = PendingIntent.getService(
            this, notifId,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP).putExtra(STOP_EXTRA_ID, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording · $title")
            .setContentText("Recording · Select Stop to Save")
            .setContentIntent(recordingsIntent(notifId))
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun finalNotification(title: String, end: CaptureEnd, notifId: Int): Notification {
        val desc = when (end) {
            is CaptureEnd.Finished -> "Complete · ${end.segments} Segments · ${"%.1f".format(end.bytes / 1e6)} MB"
            CaptureEnd.StreamLost -> "Source Interrupted · Partial File Retained"
            CaptureEnd.StorageFull -> "Storage Threshold Reached · Capture Halted"
            CaptureEnd.SizeCapped -> "Size Limit Reached · Capture Halted"
            CaptureEnd.NetworkFallback -> "Network Target Unavailable · Stored Locally"
            CaptureEnd.Stopped -> "Stopped · File Saved"
            is CaptureEnd.WriteError -> "Write Error · ${end.message}"
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Recording Saved · $title")
            .setContentText(desc)
            .setContentIntent(recordingsIntent(notifId))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }
}
