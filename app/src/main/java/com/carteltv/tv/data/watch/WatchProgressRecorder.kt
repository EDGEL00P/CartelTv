package com.carteltv.tv.data.watch

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.engine.stability.NotificationListenerGuard
import java.util.concurrent.Executors

/**
 * Notices what video apps play on this TV and saves it to [WatchHistoryStore].
 */
class WatchProgressRecorder(private val context: Context) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotificationListenerGuard::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "carteltv-history").apply { isDaemon = true } }
    private val prefs get() = context.getSharedPreferences("carteltv", Context.MODE_PRIVATE)

    private class Watch(val pkg: String, val title: String) {
        val id = "$pkg|$title"
        var subtitle: String? = null
        var positionMs = 0L
        var durationMs = 0L
        var playedMs = 0L
        var playingSince = 0L
        var art: Bitmap? = null
        var artUrl: String? = null
        var artSaved = false
        fun played(now: Long) = playedMs + if (playingSince > 0) now - playingSince else 0
    }

    private val callbacks = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val watches = HashMap<MediaSession.Token, Watch>()
    private val videoApp = HashMap<String, Boolean>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }
    private val tick = object : Runnable {
        override fun run() {
            callbacks.values.forEach { (c, _) -> update(c) }
            val now = SystemClock.elapsedRealtime()
            watches.values.filter { it.playingSince > 0 }.forEach { save(it, now) }
            if (watches.values.any { it.playingSince > 0 }) handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            attach(msm.getActiveSessions(component))
        } catch (_: SecurityException) { } // bughunt:ignore kt-empty-catch
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        val now = SystemClock.elapsedRealtime()
        watches.values.toList().forEach { end(it, now) }
        watches.clear()
        callbacks.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        handler.removeCallbacks(tick)
        // Queued saves still drain; a fresh recorder is built on every listener reconnect, so the worker must not outlive this one.
        io.shutdown()
    }

    private fun post(task: Runnable) {
        runCatching { io.execute(task) }
    }

    private fun attach(list: List<MediaController>) {
        val now = SystemClock.elapsedRealtime()
        val tokens = list.map { it.sessionToken }.toSet()
        watches.keys.filter { it !in tokens }.forEach { t -> watches.remove(t)?.let { end(it, now) } }
        callbacks.keys.filter { it !in tokens }.forEach { t ->
            callbacks.remove(t)?.let { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        }
        for (c in list) {
            if (c.sessionToken in callbacks || !isVideoApp(c.packageName)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = update(c)
                override fun onMetadataChanged(metadata: MediaMetadata?) = update(c)
                override fun onSessionDestroyed() {
                    watches.remove(c.sessionToken)?.let { end(it, SystemClock.elapsedRealtime()) }
                    callbacks.remove(c.sessionToken)
                    runCatching { c.unregisterCallback(this) }
                }
            }
            c.registerCallback(cb, handler)
            callbacks[c.sessionToken] = c to cb
            update(c)
        }
    }

    private fun update(c: MediaController) {
        val now = SystemClock.elapsedRealtime()
        val md = c.metadata
        val title = (md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            ?.trim()?.takeIf { it.isNotEmpty() }
        var w = watches[c.sessionToken]
        if (w != null && w.title != title) {
            end(w, now)
            watches.remove(c.sessionToken)
            w = null
        }
        if (md == null || title == null) return
        if (w == null) {
            w = Watch(c.packageName, title)
            watches[c.sessionToken] = w
        }
        w.subtitle = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            ?.trim()?.takeIf { it.isNotEmpty() && it != title }
        w.durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        if (!w.artSaved && w.art == null && w.artUrl == null) {
            w.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            w.artUrl = listOf(
                MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
            ).firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.startsWith("https://") } }
        }
        val ps = c.playbackState
        if (ps != null) {
            val playingNow = ps.state == PlaybackState.STATE_PLAYING
            val since = ps.lastPositionUpdateTime.takeIf { it in 1..now } ?: now
            w.positionMs = (ps.position + if (playingNow) ((now - since) * ps.playbackSpeed).toLong() else 0L).coerceAtLeast(0)
        }
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        if (playing && w.playingSince == 0L) {
            w.playingSince = now
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, TICK_MS)
        } else if (!playing && w.playingSince > 0L) {
            w.playedMs += now - w.playingSince
            w.playingSince = 0L
            save(w, now)
        }
    }

    private fun end(w: Watch, now: Long) {
        if (w.playingSince > 0L) { w.playedMs += now - w.playingSince; w.playingSince = 0L }
        save(w, now)
    }

    private fun save(w: Watch, now: Long) {
        if (!AppPreferences.historyOn) return
        if (w.played(now) < MIN_WATCH_MS) return
        val id = w.id
        if (w.durationMs > 0 && w.positionMs >= w.durationMs * WatchHistoryStore.FINISHED) {
            post { WatchHistoryStore.remove(context, id) }
            return
        }
        val pkg = w.pkg; val title = w.title; val sub = w.subtitle
        val pos = w.positionMs; val dur = w.durationMs
        val art = if (w.artSaved) null else w.art
        val artUrl = if (w.artSaved) null else w.artUrl
        w.artSaved = true; w.art = null
        post { WatchHistoryStore.record(context, id, pkg, title, sub, pos, dur, art, artUrl) }
    }

    private fun isVideoApp(pkg: String): Boolean = videoApp.getOrPut(pkg) {
        if (pkg == context.packageName || pkg in MUSIC_APPS) return@getOrPut false
        val category = runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
        category != ApplicationInfo.CATEGORY_AUDIO
    }

    companion object {
        const val HISTORY_PREF = "history_on"
        private const val MIN_WATCH_MS = 2 * 60_000L
        private const val TICK_MS = 30_000L
        private val MUSIC_APPS = setOf(
            "com.spotify.tv.android", "com.spotify.music",
            "com.google.android.apps.youtube.music", "com.google.android.youtube.tvmusic",
            "com.amazon.mp3", "com.apple.android.music", "com.aspiro.tidal",
            "deezer.android.tv", "deezer.android.app", "com.soundcloud.android",
            "com.pandora.android.atv", "com.plexapp.plexamp",
        )
    }
}
