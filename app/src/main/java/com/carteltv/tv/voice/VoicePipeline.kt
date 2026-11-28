package com.carteltv.tv.voice

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carteltv.tv.AppScope
import com.carteltv.tv.data.apps.InstalledAppsCache
import com.carteltv.tv.data.apps.AppsRepository
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.ui.settings.CompanionOverlayState
import com.carteltv.tv.nav.SectionNav
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.system.SleepTimerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class VoicePhase { IDLE, LISTENING, THINKING, DONE, ERROR }

/** Query typed or spoken for the Search screen; the screen consumes it when it appears or when it changes. */
object SearchPrefill {
    var pending by mutableStateOf<String?>(null)
}

/**
 * One command pipeline for every input: the TV mic, the phone companion, Assistant handoff and deep links
 * all end up in [submit]. Command handling and diagnostics read [phase], [heard], [message] and [level].
 */
object VoicePipeline {
    var phase by mutableStateOf(VoicePhase.IDLE)
        private set
    var heard by mutableStateOf("")
        internal set
    var message by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        internal set

    // resetJob is touched from the main thread (listening/cancel) AND AppScope.io workers
    // (resetAfter via submit, incl. companion-server requests): volatile so cancel() always
    // sees the latest job, and the whole swap is serialized so no orphan reset survives.
    @Volatile private var resetJob: Job? = null
    private val resetLock = Any()

    internal fun listening() {
        heard = ""; message = ""; phase = VoicePhase.LISTENING
        resetAfter(20_000L)
    }

    internal fun thinking() {
        if (phase == VoicePhase.LISTENING) phase = VoicePhase.THINKING
    }

    internal fun fail(msg: String) {
        message = msg; level = 0f; phase = VoicePhase.ERROR
        resetAfter(4_500L)
    }

    internal fun cancel() = synchronized(resetLock) {
        resetJob?.cancel()
        phase = VoicePhase.IDLE; heard = ""; message = ""; level = 0f
    }

    private fun resetAfter(ms: Long) = synchronized(resetLock) {
        resetJob?.cancel()
        resetJob = AppScope.io.launch {
            delay(ms)
            synchronized(resetLock) {
                // Skip if a newer state change re-armed while we waited.
                if (resetJob?.isCancelled == true) return@synchronized
                phase = VoicePhase.IDLE; heard = ""; message = ""; level = 0f
            }
        }
    }

    /** Parses and runs [text]. With [searchFallback], a phrase that isn't a command becomes a search. */
    suspend fun submit(ctx: Context, text: String, searchFallback: Boolean = false, external: Boolean = false, browsable: Boolean = false): String {
        val app = ctx.applicationContext
        heard = text.trim(); level = 0f; phase = VoicePhase.THINKING
        var intent = VoiceParser.parse(text)
        // Links from other apps or web pages may navigate and launch, but not drive devices, audio or timers.
        if (external && (intent is VoiceIntent.Volume || intent is VoiceIntent.Sleep ||
                intent is VoiceIntent.Media || intent is VoiceIntent.ShowCompanion)
        ) intent = VoiceIntent.Search(text.trim())
        // Web pages can fire the BROWSABLE carteltv:// link: allow only in-app navigation and search, never launching apps or streams.
        if (browsable && intent !is VoiceIntent.OpenSection && intent !is VoiceIntent.Search && intent !is VoiceIntent.Unknown) intent = VoiceIntent.Search(text.trim())
        // Unrecognized phrases go to the free keyless AI first; search stays the fallback.
        if (intent is VoiceIntent.Unknown && text.isNotBlank()) {
            val answer = AiAnswerFormatter.ask(text.trim())
            if (answer != null) {
                message = answer
                phase = VoicePhase.DONE
                resetAfter(9_000L) // answers are longer than status messages; keep them readable
                return answer
            }
        }
        if (searchFallback && intent is VoiceIntent.Unknown && text.isNotBlank()) intent = VoiceIntent.Search(text.trim())
        val msg = try {
            VoiceActions.run(app, intent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Failed: ${e.message ?: e.javaClass.simpleName}"
        }
        message = msg
        phase = if (intent is VoiceIntent.Unknown || msg.startsWith("Failed")) VoicePhase.ERROR else VoicePhase.DONE
        resetAfter(4_500L)
        return msg
    }
}

internal object VoiceActions {

    private fun tab(s: Section): AppSection = when (s) {
        Section.HOME -> AppSection.HOME
        Section.APPS -> AppSection.APPS
        Section.LIVE -> AppSection.LIVE
        Section.GUIDE -> AppSection.GUIDE
        Section.SPORTS -> AppSection.SPORTS
        Section.MOVIES -> AppSection.MOVIES
        Section.TV -> AppSection.TV
        Section.YOUTUBE -> AppSection.YOUTUBE
        Section.RECENTS -> AppSection.RECENTS
        Section.SEARCH -> AppSection.SEARCH
        Section.SETTINGS -> AppSection.SETUP
    }

    suspend fun run(ctx: Context, intent: VoiceIntent): String = when (intent) {
        is VoiceIntent.Sequence -> {
            val parts = intent.intents.map { run(ctx, it) }
            parts.joinToString(" · ")
        }
        is VoiceIntent.OpenSection -> withContext(Dispatchers.Main) {
            val t = tab(intent.section)
            SectionNav.go(t)
            "Opened ${t.label}"
        }
        is VoiceIntent.OpenApp -> openApp(ctx, intent.query)
        is VoiceIntent.WatchChannel -> watchChannel(ctx, intent.query)
        is VoiceIntent.Search -> withContext(Dispatchers.Main) {
            SearchPrefill.pending = intent.query
            SectionNav.go(AppSection.SEARCH)
            "Searching “${intent.query}”"
        }
        is VoiceIntent.Media -> media(ctx, intent.action)
        is VoiceIntent.Volume -> volume(ctx, intent.change)
        is VoiceIntent.Sleep -> withContext(Dispatchers.Main) {
            if (intent.minutes == null) { SleepTimerState.clear(); "Sleep timer cancelled" }
            else { SleepTimerState.armSleep(intent.minutes); "Sleep timer set for ${intent.minutes} min" }
        }
        VoiceIntent.Back -> withContext(Dispatchers.Main) { if (Nav.pop()) "Back" else "Already home" }
        VoiceIntent.ShowCompanion -> withContext(Dispatchers.Main) { CompanionOverlayState.open(); "Phone companion" }
        is VoiceIntent.Unknown -> "Didn't catch “${intent.heard}”"
    }

    private suspend fun openApp(ctx: Context, query: String): String {
        val apps = InstalledAppsCache.apps
        val i = VoiceMatch.best(query, apps.map { it.label })
        if (i != null) {
            val app = apps[i]
            if (withContext(Dispatchers.Main) { AppsRepository.launchApp(ctx, app.packageName) }) return "Launching ${app.label}"
        }
        // No such app: a live channel, then the provider on-demand search, covers "open espn", "open breaking bad".
        val client = ProviderSession.current
        if (client != null) {
            val channels = LiveChannels.all(client)
            VoiceMatch.best(query, channels.map { it.name })?.let { ci ->
                val ch = channels[ci]
                withContext(Dispatchers.Main) { AppLauncher.player(ctx, client.liveUrl(ch.streamId), ch.name, "live:${ch.streamId}") }
                return "Tuning ${ch.name}"
            }
        }
        return "No app called “$query”"
    }

    private suspend fun watchChannel(ctx: Context, query: String): String {
        val client = ProviderSession.current
        if (client != null) {
            val channels = LiveChannels.all(client)
            // A spoken number ("channel 42", or bare "42") tunes by channel number before any name matching.
            val requested = query.trim().toIntOrNull() ?: VoiceParser.numberWords[query.trim().lowercase()]
            if (requested != null) {
                val ch = channels.firstOrNull { it.num == requested } ?: return "No channel number $requested"
                withContext(Dispatchers.Main) { AppLauncher.player(ctx, client.liveUrl(ch.streamId), ch.name, "live:${ch.streamId}") }
                return "Tuning ${ch.name}"
            }
            val i = VoiceMatch.best(query, channels.map { it.name })
            if (i != null) {
                val ch = channels[i]
                withContext(Dispatchers.Main) { AppLauncher.player(ctx, client.liveUrl(ch.streamId), ch.name, "live:${ch.streamId}") }
                return "Tuning ${ch.name}"
            }
        }
        val apps = InstalledAppsCache.apps
        VoiceMatch.best(query, apps.map { it.label })?.let { i ->
            val app = apps[i]
            if (withContext(Dispatchers.Main) { AppsRepository.launchApp(ctx, app.packageName) }) return "Launching ${app.label}"
        }
        return if (client == null) "No TV provider connected, and no app called “$query”" else "No channel called “$query”"
    }

    private fun media(ctx: Context, action: Transport): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "Audio service unavailable"
        val (code, label) = when (action) {
            Transport.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY to "Playing"
            Transport.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused"
            Transport.STOP -> KeyEvent.KEYCODE_MEDIA_STOP to "Stopped"
            Transport.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT to "Next"
            Transport.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Previous"
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return label
    }

    private fun volume(ctx: Context, change: VolumeChange): String {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return "Audio service unavailable"
        val stream = AudioManager.STREAM_MUSIC
        return when (change) {
            VolumeChange.Up -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); "Volume up" }
            VolumeChange.Down -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); "Volume down" }
            VolumeChange.Mute -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); "Muted" }
            VolumeChange.Unmute -> { am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI); "Unmuted" }
            is VolumeChange.Set -> {
                val max = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, (change.percent * max / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
                "Volume ${change.percent}%"
            }
        }
    }
}
