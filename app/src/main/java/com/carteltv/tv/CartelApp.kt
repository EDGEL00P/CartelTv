package com.carteltv.tv

import com.carteltv.tv.ui.components.TransientStatus
import com.carteltv.tv.system.ImmersiveModeController
import com.carteltv.tv.core.suspendRunCatching

import android.app.ActivityManager
import android.app.Application
import coil3.SingletonImageLoader
import coil3.ImageLoader
import coil3.PlatformContext
import okio.Path.Companion.toOkioPath
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.carteltv.tv.data.apps.InstalledAppsCache
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.system.SleepTimerState

import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.carteltv.tv.data.watch.Scrobble
/**
 * App singletons. AppScope is the ONLY fire-and-forget IO scope: post-dispose
 * work (scrobble-on-close) launches here, never in ad-hoc anonymous scopes.
 */
object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class CartelApp : Application(), SingletonImageLoader.Factory {
    private val appScope get() = AppScope.io

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
            )
        }
        AppContainer().install()
        AppPreferences.init(this)
        com.carteltv.tv.ui.theme.ThemePresets.select(AppPreferences.themePreset)
        com.carteltv.tv.ui.theme.ThemeAccessibility.install(this)
        com.carteltv.tv.data.xtream.LiveChannels.cacheDir = cacheDir
        com.carteltv.tv.data.network.OkHttpTransport.cacheDir = cacheDir
        com.carteltv.tv.data.network.Connectivity.start(this)
        ProviderSession.restoreActive()
        appScope.launch {
            com.carteltv.tv.voice.VoskModelManager.refresh(this@CartelApp)
            com.carteltv.tv.engine.stability.CrashWatchdog.install(this@CartelApp)
            com.carteltv.tv.data.trakt.TraktAuth.bootstrap()
            // bootstrap must finish before refreshAccount reads TraktAuth.client; don't block the rest of init on network.
            launch { suspendRunCatching { com.carteltv.tv.data.trakt.TraktAuth.refreshAccount() } }
            InstalledAppsCache.preload(this@CartelApp)
            com.carteltv.tv.data.preferences.Profiles.load()
            com.carteltv.tv.data.preferences.Profiles.attach(this@CartelApp)
            // Google TV Watch Next: reconcile self rows with the active profile's recents
            // (publish, prune stale rows, drop completed). Same coroutine as Profiles so the
            // active profile is settled before recents are read.
            com.carteltv.tv.data.watch.WatchNextPublisher.reconcile(this@CartelApp, AppPreferences.recentEntries())
            SleepTimerState.start(this@CartelApp)
            runCatching { com.carteltv.tv.data.network.PrivateDnsController(this@CartelApp).reconcilePause() }
            com.carteltv.tv.update.ReleaseUpdater.installedCode = runCatching {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            }.getOrDefault(1)
            com.carteltv.tv.update.ReleaseUpdater.feedUrl = AppPreferences.updaterFeedUrl
            com.carteltv.tv.data.espn.ScoreboardRepository.installSnapshots(java.io.File(cacheDir, "scoreboards"))
        }
        appScope.launch {
            kotlinx.coroutines.delay(8_000)
            while (true) {
                suspendRunCatching { com.carteltv.tv.data.provider.ProviderFailover.check() }.getOrNull()?.let { p ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        TransientStatus.show("Provider unreachable: switched to ${p.name}")
                    }
                }
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
        appScope.launch {
            kotlinx.coroutines.delay(20_000)
            while (true) {
                suspendRunCatching { com.carteltv.tv.update.ReleaseUpdater.check(this@CartelApp) }
                kotlinx.coroutines.delay(24 * 60 * 60_000L)
            }
        }
        // Nothing below is needed for the first frame, so it runs off the main thread, in order.
        appScope.launch {
            // Android TV app channels + LAN PeerSync main
            com.carteltv.tv.data.provider.ChannelRepository.start(this@CartelApp)
            if (AppPreferences.peerSyncRole == "main") {
                com.carteltv.tv.data.sync.PeerSync.startMainServer(this@CartelApp)
            }
            // M8: rearm scheduled recordings after process/boot death
            com.carteltv.tv.recording.RecordingScheduler.rearmAll(this@CartelApp)
            // Daily new-episode alert scan (boot twin lives in alert/)
            com.carteltv.tv.alert.EpisodeAlertScheduler.armDaily(this@CartelApp)
            // fire anything already due (e.g., process died during a scheduled window)
            com.carteltv.tv.recording.RecordingScheduler.dueNow(this@CartelApp).forEach { job ->
                // Cold starts from the background may not be allowed to start a foreground service (API 31+).
                try {
                    com.carteltv.tv.recording.RecordingService.start(
                        this@CartelApp, job.url, job.title, com.carteltv.tv.recording.RecordingScheduler.captureDuration(job),
                    )
                    com.carteltv.tv.recording.RecordingScheduler.prune(this@CartelApp, job)
                } catch (_: IllegalStateException) {
                    com.carteltv.tv.recording.RecordingScheduler.retrySoon(this@CartelApp, job)
                }
            }
            // Crew start alerts and followed-game monitors had only a boot twin — after any
            // crash/updater/swipe-away restart they stayed dead until a full reboot.
            com.carteltv.tv.alert.KickoffAlertScheduler.rearmAll(this@CartelApp)
            com.carteltv.tv.alert.LiveScoreMonitor.rearmOnBoot(this@CartelApp)
        }
        // Phase-2a: auto-tune buffer once from real device capabilities.
        appScope.launch {
            val v = com.carteltv.tv.engine.stability.DeviceBudget.probe(this@CartelApp)
            AppPreferences.assessmentNotes = v.summaryAndNotes
            com.carteltv.tv.engine.stability.DeviceBudget.autoTuneOnce(this@CartelApp)
        }
        // Always verify notification listener state on startup when elevated permission is held.
        if (com.carteltv.tv.engine.stability.HomeAuthority.canWriteSecure(this)) {
            if (!com.carteltv.tv.engine.stability.HomeAuthority.listenerGranted(this)) {
                runCatching { com.carteltv.tv.engine.stability.HomeAuthority.grantRevivalSelf(this) }
            }
            AppPreferences.revivalGranted = true
        }
        // Immersion law: every activity re-asserts sticky lean-back at resume —
        // the status bar may be summoned by dialogs/overscroll but never stays.
        registerActivityLifecycleCallbacks(ImmersiveModeController)
    }

    // Coil builds this on first image request instead of during process start. Capped memory cache:
    // the default 25%-of-heap policy would consume hundreds of MB on TV boxes.
    override fun newImageLoader(context: PlatformContext): ImageLoader = buildImageLoader(this)

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            com.carteltv.tv.data.weather.TileCache.trim(critical = level >= TRIM_MEMORY_RUNNING_CRITICAL)
        }
        if (level >= TRIM_MEMORY_UI_HIDDEN || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            runCatching {
                SingletonImageLoader.get(this).memoryCache?.clear()
            }
        }
    }
}

/** Coil ImageLoader with a bounded memory cache (128 MB or 15% of heap, whichever is smaller). */
private fun buildImageLoader(ctx: Application): ImageLoader {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val heapBytes = am?.memoryClass?.times(1024L * 1024L) ?: 256L * 1024 * 1024
    val maxBytes = minOf(128L * 1024 * 1024, (heapBytes * 0.15).toLong())
    return ImageLoader.Builder(ctx)
        // Coil 3 ignores Cache-Control by default, which suits posters and logos: origins often send no-cache and would force refetches.
        // SVG logos decode in place like any other art; without this decoder they fail silently.
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { com.carteltv.tv.data.network.OkHttpTransport.imageClient() }))
            add(SvgDecoder.Factory())
        }
        .diskCache {
            DiskCache.Builder()
                .directory(ctx.cacheDir.resolve("images").toOkioPath())
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(maxBytes)
                .build()
        }
        .build()
}
