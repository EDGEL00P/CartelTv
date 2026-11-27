@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.player
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.carteltv.tv.AppScope
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.chapters.ChapterScrobbleStore
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.trakt.TraktAuth
import com.carteltv.tv.data.watch.EPISODE_TITLE
import com.carteltv.tv.data.watch.Scrobble
import com.carteltv.tv.data.watch.scrobbleFor
import com.carteltv.tv.engine.chapters.ChapterSkip
import com.carteltv.tv.engine.chapters.ChapterTracker
import com.carteltv.tv.engine.playback.asProgressiveTs
import com.carteltv.tv.engine.playback.servedTsForHls
import com.carteltv.tv.engine.stability.PlaybackFailure
import com.carteltv.tv.engine.stability.StreamReconnectPolicy
import com.carteltv.tv.system.SleepTimerState
import com.carteltv.tv.ui.catalog.RatingBadge
import com.carteltv.tv.ui.live.GuideData
import com.carteltv.tv.ui.player.PlayerControlState
import com.carteltv.tv.ui.player.PlayerErrorCard
import com.carteltv.tv.ui.player.PlayerHandover
import com.carteltv.tv.ui.player.PlayerMeta
import com.carteltv.tv.ui.player.PlayerOverlay
import com.carteltv.tv.ui.player.PlayerRemote
import com.carteltv.tv.ui.player.PlayerVideoView
import com.carteltv.tv.ui.player.ReportNowPlaying
import com.carteltv.tv.ui.player.UpNextCard
import com.carteltv.tv.ui.player.buildCartelPlayer
import com.carteltv.tv.ui.player.cartelExtractors
import com.carteltv.tv.ui.player.cartelHttpFactory
import com.carteltv.tv.ui.player.cartelMediaItem
import com.carteltv.tv.ui.player.hdrClass
import com.carteltv.tv.ui.player.neighbourChannel
import com.carteltv.tv.ui.player.playerSurface
import com.carteltv.tv.ui.player.providerDataSource
import com.carteltv.tv.ui.player.rememberPlayerRemote
import com.carteltv.tv.ui.player.rememberVideoGeometry
import com.carteltv.tv.ui.player.resolutionClass
import com.carteltv.tv.ui.player.videoCodecClass
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is playing: enough to start the same stream here again, or in a recreated Activity. */
private data class PlayerTune(
    val url: String,
    val title: String,
    val refId: String,
)

private val SERIES_REF = Regex("^series:(\\d+)/(.+)$")

/** What outlives a rebuilt player (a Voice boost change) but not a change of tune. */
private class PlayerSurfaceMemory {
    val deck = PlayerControlState()
    val introShown =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
}

/**
 * The intro card sits this far above the bottom edge, which clears the compact (BAR) deck if the viewer
 * calls it up while the card is still showing.
 */
private val INTRO_BOTTOM_INSET = 130.dp

/**
 * Media3 player host with overlay: state machine UI, track picker, data pool, audio sync, and
 * comprehensive controls.
 */
@Composable
fun PlayerScreenContent(
    streamUrl: String,
    title: String,
    refId: String,
    alternates: List<com.carteltv.tv.engine.playback.AlternateSource> = emptyList(),
    onOpenPictureInPicture: (() -> Unit)? = null,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    // Other ways to play this title, tried in order when the one on screen dies; each is used at most once.
    var remaining by remember { mutableStateOf(alternates) }
    // Zapping or the next episode swaps the tune in place. Everything below re-keys on it exactly as
    // a fresh screen would, so the old tune saves its progress on the way out and no Activity relaunches.
    var tune by remember { mutableStateOf(PlayerTune(streamUrl, title, refId)) }
    var handover by remember { mutableStateOf<PlayerHandover?>(null) }
    DisposableEffect(Unit) {
        // Left for the last tune to undo, so a zap between same-rate channels never bounces the display mode.
        onDispose {
            if (AppPreferences.refreshSync) {
                com.carteltv.tv.engine.playback.RefreshRateSync
                    .restore(context)
            }
        }
    }
    key(tune) {
        val surface = remember { PlayerSurfaceMemory() }
        key(handover) {
            PlayerSurface(
                tune = tune,
                handover = handover,
                memory = surface,
                onTune = { next ->
                    // A recreated Activity (low memory, developer options) must land on what is on screen now.
                    (context as? android.app.Activity)
                        ?.intent
                        ?.putExtra(
                            "url",
                            next.url,
                        )?.putExtra("title", next.title)
                        ?.putExtra("refId", next.refId)
                    handover = null
                    tune = next
                },
                onRebuild = { handover = it },
                hasAlternates = { remaining.isNotEmpty() },
                nextSource = {
                    val (link, rest) =
                        com.carteltv.tv.engine.playback.AlternateSourceSelector.firstPlayable(remaining) { alt ->
                            alt.infoHash?.let {
                                com.carteltv.tv.data.stremio.StremioAddons
                                    .resolveTorrent(it, alt.sources)
                            }
                        }
                    remaining = rest
                    link
                },
                onSourceChange = { next, resumeAt ->
                    (context as? android.app.Activity)?.intent?.putExtra("url", next.url)
                    handover = resumeAt
                    tune = next
                },
                onOpenPictureInPicture = onOpenPictureInPicture,
                onClosed = onClosed,
            )
        }
    }
}

@Composable
private fun PlayerSurface(
    tune: PlayerTune,
    handover: PlayerHandover?,
    memory: PlayerSurfaceMemory,
    onTune: (PlayerTune) -> Unit,
    onRebuild: (PlayerHandover) -> Unit,
    hasAlternates: () -> Boolean,
    nextSource: suspend () -> String?,
    onSourceChange: (PlayerTune, PlayerHandover) -> Unit,
    onOpenPictureInPicture: (() -> Unit)?,
    onClosed: () -> Unit,
) {
    val streamUrl = tune.url
    val title = tune.title
    val refId = tune.refId
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLive = refId.startsWith("live:")

    // Provider-declared headers/DRM (M3U only); null from other providers is a no-op.
    val streamMeta =
        remember(streamUrl) {
            com.carteltv.tv.data.xtream.ProviderSession.current
                ?.streamMeta(streamUrl)
        }

    val player =
        remember(streamUrl) {
            if (isLive) {
                com.carteltv.tv.engine.captions.LiveCaptions
                    .reset()
            }
            // Live progressive retries must not honour the reached byte position (see LiveRetryDataSource).
            val http = cartelHttpFactory(streamMeta?.headers.orEmpty())
            val extractors = cartelExtractors(context)
            buildCartelPlayer(
                context,
                live = isLive,
                deepBuffer = AppPreferences.deepBufferActive,
                sourceFactory =
                    com.carteltv.tv.engine.playback.LenientMediaSourceFactory(
                        providerDataSource(
                            context,
                            if (isLive) {
                                com.carteltv.tv.engine.playback.LiveRetryDataSource
                                    .Factory(http)
                            } else {
                                http
                            },
                        ),
                        if (isLive) {
                            com.carteltv.tv.engine.captions.LiveCaptions
                                .Extractors(extractors)
                        } else {
                            extractors
                        },
                        captions = isLive,
                        // Segment failures surface quickly to the recovery ladder below instead of burning
                        // Media3's stock in-URL retries; VOD keeps the stock policy.
                        loadErrorPolicy =
                            if (isLive) {
                                com.carteltv.tv.engine.stability
                                    .LiveLoadErrorPolicy()
                            } else {
                                null
                            },
                    ),
            ).build().also { p ->
                // GL post-processing (deband + sharpen) for low-bitrate rips. Must land
                // before the first prepare(), which happens further down this effect.
                if (AppPreferences.videoEnhance) {
                    // The GL graph renders black frames on GLES stacks without EXT_YUV_target (SHIELD among
                    // them), so those devices watch the stream direct from the decoder instead.
                    if (com.carteltv.tv.engine.playback.VideoEnhancement
                            .deviceSupportsGlPostProcessing()
                    ) {
                        p.setVideoEffects(
                            listOf(
                                com.carteltv.tv.engine.playback
                                    .VideoEnhancement(),
                            ),
                        )
                    }
                }
            }
        }

    var playbackError by remember(streamUrl) { mutableStateOf<String?>(null) }
    var playbackDetail by remember(streamUrl) { mutableStateOf<String?>(null) }
    var sessionConflict by remember(streamUrl) { mutableStateOf(false) }
    val videoViews = remember { mutableListOf<PlayerView>() }
    val passOut =
        remember(streamUrl) {
            com.carteltv.tv.engine.stability.ViewerPresenceGuard(
                AppPreferences.passOutHours * com.carteltv.tv.engine.stability.ViewerPresenceGuard.HOUR_MS,
            )
        }
    var passOutLeft by remember(streamUrl) { mutableStateOf<Int?>(null) }
    val thumbs =
        remember(streamUrl) {
            val base =
                com.carteltv.tv.data.xtream.ProviderSession.current
                    ?.baseUrl
            if (AppPreferences.seekThumbs &&
                com.carteltv.tv.engine.playback.StreamLinkCheck
                    .shouldProbe(streamUrl, refId, base)
            ) {
                com.carteltv.tv.engine.playback
                    .SeekThumbnails(streamUrl)
            } else {
                null
            }
        }
    DisposableEffect(thumbs) { onDispose { thumbs?.release() } }
    var thumbFrame by remember(streamUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var skipSegments by remember(streamUrl) { mutableStateOf(emptyList<com.carteltv.tv.engine.chapters.ChapterSkip.Segment>()) }
    var fileChapters by remember(streamUrl) { mutableStateOf(emptyList<ChapterSkip.Chapter>()) }
    var activeSkip by remember(streamUrl) { mutableStateOf<com.carteltv.tv.engine.chapters.ChapterSkip.Segment?>(null) }

    var introUp by remember(streamUrl) { mutableStateOf(false) }
    val introShown = memory.introShown
    val resumeMs = remember(streamUrl) { if (refId.startsWith("live:")) 0L else AppPreferences.titleResumeMs(refId) }
    var introCert by remember(streamUrl) { mutableStateOf<com.carteltv.tv.data.discovery.Classifications.Rating?>(null) }
    var introTech by remember(streamUrl) { mutableStateOf("") }
    var introArt by remember(streamUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(introUp) {
        if (!introUp || refId.startsWith("live:")) return@LaunchedEffect
        suspendRunCatching {
            com.carteltv.tv.data.stremio.OnlineTitleInfoLoader
                .load(refId)
        }.getOrNull()?.let {
            introArt = it.episodeThumb ?: it.poster
        }
    }
    LaunchedEffect(streamUrl) {
        if (!refId.startsWith("live:") && !refId.startsWith("recording:")) {
            introCert =
                com.carteltv.tv.data.discovery.Classifications.forTitle(
                    if (refId.startsWith("series:")) "tv" else "movie",
                    title,
                )
        }
    }
    LaunchedEffect(introUp) {
        if (!introUp) return@LaunchedEffect
        // Give track selection a beat so the technical line reads the real formats, not placeholders.
        delay(600L)
        val f = player.videoFormat
        introTech =
            if (f == null) {
                ""
            } else {
                listOfNotNull(
                    resolutionClass(f.width, f.height).takeIf { it.isNotBlank() },
                    videoCodecClass(f.codecs, f.sampleMimeType).takeIf { it.isNotBlank() },
                    hdrClass(f.colorInfo, f.codecs).takeIf { it != "SDR" },
                ).joinToString(" \u00b7 ")
            }
        delay(7_000L)
        introUp = false
    }

    data class NextEpisode(
        val url: String,
        val refId: String,
        val title: String,
    )
    var nextEp by remember(refId) { mutableStateOf<NextEpisode?>(null) }
    LaunchedEffect(refId) {
        val m = SERIES_REF.find(refId) ?: return@LaunchedEffect
        val seriesId = m.groupValues[1].toLongOrNull() ?: return@LaunchedEffect
        val curId = m.groupValues[2]
        val client = com.carteltv.tv.data.xtream.ProviderSession.current ?: return@LaunchedEffect
        val seasons =
            withContext(Dispatchers.IO) {
                suspendRunCatching { client.seriesEpisodes(seriesId) }.getOrNull().orEmpty()
            }
        val flat = seasons.keys.sorted().flatMap { s -> seasons[s].orEmpty().map { s to it } }
        val idx = flat.indexOfFirst { it.second.id == curId }
        val (season, ep) = flat.getOrNull(idx + 1) ?: return@LaunchedEffect
        val show =
            EPISODE_TITLE
                .find(title)
                ?.groupValues[1]
                ?.trim()
                ?.takeUnless { it.isBlank() } ?: title
        nextEp =
            NextEpisode(
                url = client.episodeUrl(ep.id, ep.ext),
                refId = "series:$seriesId/${ep.id}",
                title = "$show S$season E${ep.episodeNum}: ${ep.title}",
            )
    }

    /** Swap to the next episode in place; the old episode's progress saves in dispose. */
    fun playNext(ne: NextEpisode) = onTune(PlayerTune(ne.url, ne.title, ne.refId))

    /** Channel up/down: the next channel in the guide's order. */
    fun zap(direction: Int) {
        neighbourChannel(refId, direction)?.let { onTune(PlayerTune(it.url, it.title, it.refId)) }
    }

    val numberEntry = remember(refId) { NumberEntry() }
    var typedNumber by remember(refId) { mutableStateOf("") }
    var numberNotice by remember(refId) { mutableStateOf<String?>(null) }
    LaunchedEffect(typedNumber) {
        if (typedNumber.isEmpty()) return@LaunchedEffect
        if (typedNumber.length < 4) delay(1_500L)
        val wanted = numberEntry.take()
        typedNumber = ""
        val slot = wanted?.let { channelSlotByNumber(it) }
        if (slot != null) {
            onTune(PlayerTune(slot.url, slot.title, slot.refId))
        } else if (wanted != null) {
            numberNotice = "No channel $wanted"
            delay(1_800L)
            numberNotice = null
        }
    }

    fun goToLastChannel(): Boolean =
        lastChannelSlot(refId)?.let {
            onTune(PlayerTune(it.url, it.title, it.refId))
            true
        } ?: false

    var upNextCancelled by remember(streamUrl) { mutableStateOf(false) }
    var upNextCountdownMs by remember(streamUrl) { mutableStateOf<Long?>(null) }

    BackHandler { onClosed() }

    val backgrounded =
        remember(player) {
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Leaving the screen must not leave a decoder and a provider connection slot running unseen.
    DisposableEffect(player, lifecycleOwner) {
        val live = refId.startsWith("live:")
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        backgrounded.set(true)
                        runCatching { if (live) player.stop() else player.pause() }
                    }

                    androidx.lifecycle.Lifecycle.Event.ON_START -> {
                        if (backgrounded.getAndSet(false)) {
                            // For live, a stopped player must prepare before play; for VOD the user
                            // may have paused before backgrounding — resume only what was playing.
                            runCatching { if (live) player.prepare() }
                            runCatching { if (live || player.playWhenReady) player.play() }
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(streamUrl, player) {
        com.carteltv.tv.engine.stability.BackgroundWorkScheduler
            .enterPlayback()
        val syncRate = AppPreferences.refreshSync
        // Our matcher owns the refresh rate while the setting is on; the player's own seamless-only switching would fight it.
        if (syncRate) player.setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        val isLive = refId.startsWith("live:")
        val policy = StreamReconnectPolicy(jitter = 0.25)
        val ladder =
            com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy(policy).also {
                it.attemptStarted(android.os.SystemClock.elapsedRealtime(), newTune = true)
            }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        // A retry with no network only burns an attempt, so it waits for the network (at most 30 s) before preparing.
        val retry =
            object : Runnable {
                var waitedMs = 0L

                override fun run() {
                    if (backgrounded.get()) return
                    if (!com.carteltv.tv.data.network.Connectivity.isOnline && waitedMs < 30_000L) {
                        waitedMs += 1_000L
                        handler.postDelayed(this, 1_000L)
                        return
                    }
                    waitedMs = 0L
                    ladder.attemptStarted(android.os.SystemClock.elapsedRealtime())
                    player.prepare()
                }
            }
        // A rebuffer that outlasts the retries is a dead stream: say so, rather than spin with nothing left to try.
        val stall =
            Runnable {
                if (backgrounded.get()) return@Runnable
                if (policy.nextDelayMs() != null) {
                    player.stop()
                    player.prepare()
                } else {
                    playbackError = "The stream stopped. Wait a moment, then retry."
                    playbackDetail = null
                }
            }
        // A hung hardware decoder keeps audio playing and state READY while the picture freezes;
        // no rendered frames across two ticks means reconnect.
        var lastRendered = -1
        var frozenTicks = 0
        val freezeCheck =
            object : Runnable {
                override fun run() {
                    val playing =
                        !backgrounded.get() && player.playbackState == Player.STATE_READY &&
                            player.isPlaying && player.videoFormat != null
                    val counters = if (playing) player.videoDecoderCounters else null
                    if (counters == null) {
                        frozenTicks = 0
                        lastRendered = -1
                    } else {
                        counters.ensureUpdated()
                        val n = counters.renderedOutputBufferCount
                        if (n == lastRendered) {
                            if (++frozenTicks >= 2 && policy.nextDelayMs() != null) {
                                frozenTicks = 0
                                lastRendered = -1
                                player.stop()
                                player.prepare()
                            }
                        } else {
                            frozenTicks = 0
                            lastRendered = n
                        }
                    }
                    handler.postDelayed(this, 1500L)
                }
            }
        if (isLive) handler.postDelayed(freezeCheck, 3000L)
        // Gradual live cushion: bursty IPTV servers bank reserve via 0.96x speed instead of a deep start buffer.
        // Disabled under MAT passthrough - the receiver cannot change playback speed.
        if (isLive && !AppPreferences.matTrueHd) {
            val cushion =
                com.carteltv.tv.engine.stability
                    .LiveBufferCushion()
            val cushionTick =
                object : Runnable {
                    override fun run() {
                        if (!backgrounded.get() && player.isPlaying && player.playbackState == Player.STATE_READY) {
                            val want = cushion.onBufferedMs(runCatching { player.totalBufferedDuration }.getOrDefault(0L))
                            // Ride on top of the per-title speed, re-read each tick so overlay changes are not stomped.
                            val target = AppPreferences.titleSpeed(refId) * want
                            val cur =
                                runCatching { player.playbackParameters }.getOrDefault(
                                    androidx.media3.common.PlaybackParameters(1f, 1f),
                                )
                            if (kotlin.math.abs(cur.speed - target) > 0.001f) {
                                runCatching { player.playbackParameters = cur.withSpeed(target) }
                            }
                        }
                        handler.postDelayed(this, 1_000L)
                    }
                }
            handler.postDelayed(cushionTick, 3_000L)
        }
        val failover =
            com.carteltv.tv.data.xtream
                .StreamCopyFailover()
        var activeId = refId.removePrefix("live:").toLongOrNull() ?: 0L
        // Reconnects have run out: try the channel's other quality copies. Only the URL changes,
        // so favourites, recents and the guide keep the channel the viewer picked.
        var blwFixes = 0
        var tsRetried = false
        var autoTracksDone = false
        var switchingSource = false

        fun switchToCopy(): Boolean {
            if (!isLive || activeId == 0L) return false
            val client = com.carteltv.tv.data.xtream.ProviderSession.current ?: return false
            val pool =
                com.carteltv.tv.data.xtream.LiveChannels
                    .cached()
            val selected = pool.firstOrNull { it.streamId == refId.removePrefix("live:").toLongOrNull() } ?: return false
            val next = failover.next(selected, activeId, pool, System.currentTimeMillis()) ?: return false
            activeId = next.streamId
            policy.reset()
            ladder.attemptStarted(android.os.SystemClock.elapsedRealtime(), newTune = true)
            blwFixes = 0
            tsRetried = false
            com.carteltv.tv.engine.captions.LiveCaptions
                .reset()
            val url = client.liveUrl(next.streamId)
            player.setMediaItem(cartelLiveMediaItem(url, client.streamMeta(url)))
            player.prepare()
            return true
        }

        fun scheduleRetry(): Boolean {
            val d = policy.nextDelayMs() ?: return false
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, d)
            return true
        }

        // Down the ladder: a failure that cannot heal goes to the next copy at once, others reload with backoff.
        fun recover(failure: com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy.Failure): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            return when (val step = ladder.onFailure(failure, now)) {
                is com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy.Step.Reload -> {
                    // The next attempt starts after the backoff; the startup timer must not run during it.
                    ladder.attemptStarted(now + step.delayMs)
                    handler.removeCallbacks(retry)
                    handler.postDelayed(retry, step.delayMs)
                    true
                }

                com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy.Step.NextCopy -> {
                    switchToCopy()
                }
            }
        }
        val startupWatch =
            object : Runnable {
                override fun run() {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!backgrounded.get() && playbackError == null && player.playbackState != Player.STATE_READY &&
                        ladder.startupTimedOut(now)
                    ) {
                        player.stop()
                        if (!recover(com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy.Failure.TRANSIENT)) {
                            playbackError =
                                "The stream did not start. Wait a moment, then retry."
                        }
                    }
                    handler.postDelayed(this, 2_000L)
                }
            }
        if (isLive) handler.postDelayed(startupWatch, 4_000L)
        val rateProbe =
            com.carteltv.tv.engine.playback
                .FrameRateProbe()

        fun syncRefresh(fps: Float) {
            handler.post {
                val surface = (videoViews.firstOrNull()?.videoSurfaceView as? android.view.SurfaceView)?.holder?.surface
                com.carteltv.tv.engine.playback.RefreshRateSync
                    .apply(context, surface, fps)
            }
        }
        val probeDone =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val frameListener =
            androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
                if (format.frameRate <= 0f && !probeDone.get()) {
                    rateProbe.add(presentationTimeUs)
                    // 16-frame filtered estimate switches the display mode early; the full probe is fallback.
                    (rateProbe.earlyEstimate() ?: rateProbe.estimate())?.let {
                        if (probeDone.compareAndSet(false, true)) syncRefresh(it)
                    }
                }
            }
        if (syncRate) player.setVideoFrameMetadataListener(frameListener)
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (!syncRate) return
                    val reported = player.videoFormat?.frameRate?.takeIf { it > 0f }
                    if (reported != null) {
                        syncRefresh(
                            com.carteltv.tv.engine.playback.FrameRateProbe
                                .snapToStandardRate(reported),
                        )
                    } else {
                        rateProbe.reset()
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (isLive || autoTracksDone || tracks.groups.none { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }) return
                    autoTracksDone = true
                    // System caption defaults: an explicit in-app language always wins; otherwise CaptioningManager decides.
                    val captions =
                        com.carteltv.tv.engine.captions.SystemCaptions
                            .defaults(context)
                    applyTrackChoice(
                        player,
                        chooseTracks(
                            getTrackList(tracks, TrackType.AUDIO),
                            getTrackList(tracks, TrackType.SUBTITLE),
                            AppPreferences.preferredAudioLanguage,
                            com.carteltv.tv.engine.captions.SystemCaptions.subtitlePref(
                                AppPreferences.preferredSubtitleLanguage,
                                captions,
                                java.util.Locale
                                    .getDefault()
                                    .language,
                            ),
                            java.util.Locale
                                .getDefault()
                                .language,
                        ),
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        playbackError = null
                        policy.reset()
                        ladder.onReady(android.os.SystemClock.elapsedRealtime())
                        // First ready of the stream raises the intro title card, once.
                        if (introShown.compareAndSet(false, true)) introUp = true
                    }
                    handler.removeCallbacks(stall)
                    if (isLive && playbackState == Player.STATE_BUFFERING &&
                        player.playWhenReady
                    ) {
                        handler.postDelayed(stall, PlaybackStallGuard.STALL_MS)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        // Episodic VOD auto-advances (unless Up Next is Off); anything else leaves the player.
                        val ne = nextEp
                        if (ne != null && !upNextCancelled && AppPreferences.upNextMode != "off") playNext(ne) else onClosed()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("CartelPlayer", "Playback error [${error.errorCodeName}]: ${error.message}", error)
                    val limit = PlaybackFailure.of(error) == PlaybackFailure.SessionLimit
                    if (limit) {
                        // Retrying a refused slot only deepens the provider's lockout.
                        sessionConflict = com.carteltv.tv.recording.RecordingService.capturing
                        playbackError = "Your provider's connection limit has been reached" +
                            if (sessionConflict) ". A recording is using your only connection." else ". Wait a minute, then retry."
                        return
                    }
                    if (isLive && !tsRetried && error.servedTsForHls()) {
                        // The link is typed HLS but sends plain MPEG-TS: read it as progressive, once.
                        tsRetried = true
                        player.currentMediaItem?.let { player.setMediaItem(it.asProgressiveTs()) }
                        player.prepare()
                        return
                    }
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && blwFixes < 2) {
                        blwFixes++
                        player.seekToDefaultPosition()
                        player.prepare()
                        return
                    }
                    // After an error ExoPlayer keeps its position, so prepare() resumes a VOD where it dropped.
                    if (isLive) {
                        if (recover(
                                com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy
                                    .classify(error),
                            )
                        ) {
                            return
                        }
                    } else if (PlaybackFailure.isTransientNetwork(error.errorCode) && scheduleRetry()) {
                        return
                    }
                    if (!isLive && !switchingSource && hasAlternates()) {
                        // This source cannot play: carry on from the same point with the next one.
                        switchingSource = true
                        playbackError = "This source failed. Trying another\u2026"
                        scope.launch {
                            val link = nextSource()
                            if (link != null) {
                                onSourceChange(tune.copy(url = link), PlayerHandover(player.currentPosition.coerceAtLeast(0L), true))
                            } else {
                                playbackError = PlaybackFailure.userMessage(error)
                                playbackDetail = error.errorCodeName
                            }
                            switchingSource = false
                        }
                        return
                    }
                    playbackError = PlaybackFailure.userMessage(error)
                    playbackDetail = error.errorCodeName
                }
            }
        player.addListener(listener)
        val stats =
            com.carteltv.tv.engine.playback
                .PlaybackStatistics(if (isLive) "live:$title" else "vod:$title")
        player.addAnalyticsListener(stats)
        val session =
            com.carteltv.tv.engine.playback.PlaybackSession(
                context,
                player,
                isLive,
                onStop = onClosed,
            )

        if (refId.startsWith("live:")) refId.removePrefix("live:").toLongOrNull()?.let { AppPreferences.noteRecentLive(it) }
        // The Media3 session's launcher card reads the item's metadata, as the platform session
        // it replaces read its title/subtitle pair.
        player.setMediaItem(
            (if (isLive) cartelLiveMediaItem(streamUrl, streamMeta) else cartelMediaItem(streamUrl, streamMeta))
                .buildUpon()
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata
                        .Builder()
                        .setTitle(title)
                        .apply { if (isLive) setArtist("Live") }
                        .build(),
                ).build(),
        )
        player.setPlaybackSpeed(AppPreferences.titleSpeed(refId))
        player.prepare()
        // A rebuilt player picks up where the old one stood; a new tune resumes where the viewer left it.
        val swapAt = if (isLive) null else handover?.positionMs
        if (swapAt != null) {
            if (swapAt > 0) player.seekTo(swapAt)
        } else if (resumeMs > 0) {
            com.carteltv.tv.engine.stability.BackgroundWorkScheduler
                .onSeek()
            player.seekTo(
                com.carteltv.tv.engine.playback.SeekAccumulator
                    .resumeFrom(resumeMs),
            )
        }
        player.playWhenReady = handover?.playing ?: true

        onDispose {
            com.carteltv.tv.engine.stability.BackgroundWorkScheduler
                .exitPlayback()
            val dur = runCatching { player.duration }.getOrDefault(0L)
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            val spd = runCatching { player.playbackParameters.speed }.getOrDefault(1f)
            handler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            player.removeAnalyticsListener(stats)
            stats.logSummary()
            player.clearVideoFrameMetadataListener(frameListener)
            session.release()
            player.release()

            scrobbleFor(refId, title, pos, dur)?.let { s ->
                AppScope.io.launch {
                    when (s) {
                        is Scrobble.Episode -> TraktAuth.scrobbleEpisode(s.show, s.season, s.episode)
                        is Scrobble.Movie -> TraktAuth.scrobbleMovie(s.title, null)
                    }
                }
            }
            savePlaybackProgress(refId, title, streamUrl, pos, dur, spd)
        }
    }

    ReportNowPlaying(player, title, if (refId.startsWith("live:")) "Live" else null)
    ChapterProgress(player, refId, fileChapters)

    LaunchedEffect(streamUrl) {
        var scans = 0
        while (true) {
            val tickNow = android.os.SystemClock.elapsedRealtime()
            when (passOut.check(tickNow, player.playWhenReady && player.playbackState == Player.STATE_READY)) {
                com.carteltv.tv.engine.stability.ViewerPresenceGuard.State.ASK -> {
                    if (passOutLeft == null) player.pause()
                    passOutLeft = passOut.secondsLeft(tickNow)
                }

                com.carteltv.tv.engine.stability.ViewerPresenceGuard.State.STOP -> {
                    passOutLeft = null
                    onClosed()
                }

                com.carteltv.tv.engine.stability.ViewerPresenceGuard.State.OK -> {
                    passOutLeft = null
                }
            }
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            if (fileChapters.isEmpty() && scans < 40 && player.duration > 0) {
                scans++
                val found = chaptersOf(player)
                if (found.isNotEmpty()) fileChapters = found
                skipSegments =
                    com.carteltv.tv.engine.chapters.ChapterSkip
                        .segments(found, player.duration)
            }
            activeSkip =
                com.carteltv.tv.engine.chapters.ChapterSkip
                    .active(skipSegments, pos)
            upNextCountdownMs =
                if (nextEp != null && !upNextCancelled && AppPreferences.upNextMode == "early" &&
                    player.playbackState == Player.STATE_READY
                ) {
                    val dur = runCatching { player.duration }.getOrDefault(0L)
                    val remaining = dur - pos
                    if (dur > 0 && remaining in 1..30_000L) remaining else null
                } else {
                    null
                }
            delay(250L)
        }
    }

    val liveId = remember(refId) { if (refId.startsWith("live:")) refId.removePrefix("live:").toLongOrNull() else null }
    val liveStream =
        remember(liveId) {
            liveId?.let { id ->
                com.carteltv.tv.data.xtream.LiveChannels
                    .cached()
                    .firstOrNull { it.streamId == id }
            }
        }
    var guideSlots by remember(liveId) { mutableStateOf(emptyList<com.carteltv.tv.data.xtream.GuideModel.Slot>()) }
    LaunchedEffect(liveId) {
        val id = liveId ?: return@LaunchedEffect
        val client = com.carteltv.tv.data.xtream.ProviderSession.current ?: return@LaunchedEffect
        suspendRunCatching { GuideData.load(context, client, id) }
        guideSlots =
            com.carteltv.tv.ui.live.GuideData.slots[id]
                .orEmpty()
    }
    val meta =
        remember(streamUrl, title, refId, guideSlots, liveStream, nextEp) {
            val now = System.currentTimeMillis()
            val current =
                guideSlots.firstOrNull {
                    com.carteltv.tv.data.xtream.GuideModel
                        .phase(it, now) ==
                        com.carteltv.tv.data.xtream.GuideModel.Phase.LIVE
                }
            val upcoming = guideSlots.filter { it.startMs > now }.take(3)
            val next = upcoming.firstOrNull()
            PlayerMeta(
                title = title,
                subtitle =
                    if (refId.startsWith("live:")) {
                        "Live"
                    } else if (refId.startsWith("recording:")) {
                        "Recording"
                    } else {
                        ""
                    },
                isLive = refId.startsWith("live:"),
                refId = refId,
                nextTitle = next?.title.orEmpty(),
                nextEpisodeLabel = nextEp?.title.orEmpty(),
                nextRefId = nextEp?.refId.orEmpty(),
                streamUrl = if (refId.startsWith("live:")) streamUrl else "",
                channelName =
                    com.carteltv.tv.data.xtream.GuideModel
                        .displayName(liveStream?.name ?: title),
                logoUrl = liveStream?.icon,
                channelNumber =
                    liveStream?.let {
                        channelNumberOf(
                            com.carteltv.tv.data.xtream.LiveChannels
                                .cached(),
                            it,
                        )
                    } ?: 0,
                nowTitle = current?.title.orEmpty(),
                nowStartMs = current?.startMs ?: 0L,
                nowStopMs = current?.stopMs ?: 0L,
                nextStartMs = next?.startMs ?: 0L,
                queueTitles = upcoming.map { it.title }.filter { it.isNotBlank() },
            )
        }

    val geometry = rememberVideoGeometry(refId)

    LaunchedEffect(player) {
        snapshotFlow { SleepTimerState.mediaStopAtMs }.collectLatest { at ->
            if (at > 0) {
                delay((at - System.currentTimeMillis()).coerceAtLeast(0))
                if (SleepTimerState.mediaStopAtMs == at) {
                    player.pause()
                    SleepTimerState.clearMedia()
                }
            }
        }
    }

    // The card's own rule reads the remote, so the remote is named before it is built.
    lateinit var remote: PlayerRemote
    remote =
        rememberPlayerRemote(
            player = player,
            live = { isLive },
            deck = memory.deck,
            onClose = onClosed,
        ) {
            intercept = { ev ->
                // "Are you still watching?" owns the screen: any key but Back keeps playing.
                val wasAsking = passOut.asking
                passOut.input(android.os.SystemClock.elapsedRealtime())
                if (wasAsking && ev.key != Key.Back) {
                    passOutLeft = null
                    player.play()
                    true
                } else {
                    false
                }
            }
            onNext = if (isLive) ({ zap(1) }) else ({ nextEp?.let(::playNext) })
            onPrevious = if (isLive) ({ zap(-1) }) else null
            // A lone 0 goes back to the last channel, as on a TV remote; other digits build a channel number.
            onDigit =
                if (isLive) {
                    (
                        { digit ->
                            if (digit == 0 && !numberEntry.active) {
                                goToLastChannel()
                            } else {
                                numberEntry.press(digit)
                                typedNumber = numberEntry.text
                                true
                            }
                        }
                    )
                } else {
                    null
                }
            onLast = if (isLive) ({ goToLastChannel() }) else null
            // Any input waves off the intro title card without eating the key's normal action.
            onAnyKey = { introUp = false }
            // OK jumps past an intro or recap that is on; otherwise it shows the deck.
            onOk = {
                activeSkip?.let {
                    player.seekTo(it.endMs)
                    true
                } ?: false
            }
            upNextVisible =
                {
                    upNextCountdownMs != null && nextEp != null && remote.seekTarget == null &&
                        memory.deck.value == PlayerControlState.HIDDEN &&
                        playbackError == null
                }
            onCancelUpNext = { upNextCancelled = true }
            errorUp = { playbackError != null }
            seekThumb = { thumbFrame }
        }

    LaunchedEffect(
        remote.seekTarget?.let {
            com.carteltv.tv.engine.playback.SeekThumbnails
                .bucket(it)
        },
    ) {
        val target = remote.seekTarget
        if (thumbs == null || target == null) {
            thumbFrame = null
            return@LaunchedEffect
        }
        delay(150L)
        thumbs.frameAt(target)?.let { thumbFrame = it }
    }
    // On screen only over bare video: not while a seek is pending, the deck is up, or playback has failed.
    val cardUp =
        upNextCountdownMs != null && nextEp != null &&
            remote.seekTarget == null && remote.deck.value == PlayerControlState.HIDDEN && playbackError == null

    fun retryPlayback() {
        if (!sessionConflict) {
            playbackError = null
            playbackDetail = null
            player.prepare()
            player.playWhenReady = true
            return
        }
        // The provider will keep refusing while the recording holds the connection, so that stops first.
        com.carteltv.tv.recording.RecordingService
            .stopCapture(context)
        sessionConflict = false
        scope.launch {
            delay(2_000L)
            playbackError = null
            playbackDetail = null
            player.prepare()
            player.playWhenReady = true
        }
    }

    // The video is the backdrop source for the deck's glass. SurfaceView frames may not be
    // capturable on every device; where they are not, Haze falls back to the smoked tint.
    val glassState = rememberHazeState(blurEnabled = !com.carteltv.tv.ui.theme.ThemePresets.reduceTransparency)
    androidx.compose.runtime.CompositionLocalProvider(com.carteltv.tv.ui.theme.LocalGlassBackdrop provides glassState) {
        Box(
            Modifier.fillMaxSize().background(PlayerChrome.floor).playerSurface(remote),
        ) {
            PlayerVideoView(player, geometry, Modifier.fillMaxSize().hazeSource(state = glassState)) { view ->
                videoViews.clear()
                videoViews.add(view)
            }

            PlayerOverlay(
                player = player,
                meta = meta,
                remote = remote,
                onPlayNext = nextEp?.let { ne -> { playNext(ne) } },
                geometry = geometry,
                onVoiceBoostChange = {
                    // Renderers are fixed when a player is built, so the player is swapped for one with the new chain.
                    onRebuild(PlayerHandover(player.currentPosition, player.playWhenReady))
                },
                onOpenPictureInPicture = onOpenPictureInPicture,
                chapters = fileChapters.map { it.startMs },
            )

            if (typedNumber.isNotEmpty() || numberNotice != null) {
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = ThemeSpec.Safe.vertical, end = ThemeSpec.Safe.horizontal)
                        .playerGlass(RoundedCornerShape(ThemeSpec.Card.cornerLg), heavy = true, elevation = 18.dp)
                        .padding(horizontal = ThemeSpec.Space.xl, vertical = ThemeSpec.Space.md),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (typedNumber.isNotEmpty()) {
                        Text(
                            typedNumber,
                            fontSize = ThemeSpec.Type.display,
                            fontWeight = FontWeight.Bold,
                            fontFamily = ThemeSpec.Font.mono,
                            color = PlayerChrome.ink,
                            maxLines = 1,
                        )
                        typedNumber.toIntOrNull()?.let { channelSlotByNumber(it) }?.let {
                            Text(
                                it.title,
                                fontSize = ThemeSpec.Type.meta,
                                color = PlayerChrome.inkMuted,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            numberNotice.orEmpty(),
                            fontSize = ThemeSpec.Type.title,
                            fontWeight = FontWeight.SemiBold,
                            color = PlayerChrome.ink,
                            maxLines = 1,
                        )
                    }
                }
            }

            passOutLeft?.let { left ->
                Box(Modifier.fillMaxSize().background(ThemeSemantics.scrimModal), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .widthIn(max = 560.dp)
                            .playerGlass(RoundedCornerShape(ThemeSpec.Card.cornerLg), heavy = true)
                            .padding(ThemeSpec.Space.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(ThemeSemantics.accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            PlayerGlyphIcon(PlayerGlyph.HISTORY, ThemeSemantics.accentBright, 26.dp)
                        }
                        Spacer(Modifier.height(ThemeSpec.Space.md))
                        Text(
                            "Are you still watching?",
                            fontSize = ThemeSpec.Type.titleL,
                            fontWeight = FontWeight.Bold,
                            fontFamily = ThemeSpec.Font.broadcast,
                            color = PlayerChrome.ink,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(ThemeSpec.Space.sm))
                        Text(
                            "Press any button to keep playing. Playback stops in $left seconds.",
                            fontSize = ThemeSpec.Type.meta,
                            color = PlayerChrome.inkMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = introUp && playbackError == null,
                // The house entrance. The exit is the short one: any key waves the card away, and that must not feel slow.
                enter = fadeIn(ThemeSpec.Motion.tweenSlow()) + slideInVertically(ThemeSpec.Motion.tweenSlow()) { it / 4 },
                exit = fadeOut(ThemeSpec.Motion.tweenMid()) + slideOutVertically(ThemeSpec.Motion.tweenMid()) { it / 6 },
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(androidx.compose.ui.graphics.Color.Transparent, PlayerChrome.scrimBottom.copy(alpha = 0.72f)),
                            ),
                        ).padding(
                            start = ThemeSpec.Space.margin,
                            end = ThemeSpec.Space.margin,
                            top = ThemeSpec.Space.lg,
                            bottom = INTRO_BOTTOM_INSET,
                        ),
                ) {
                    Row(
                        Modifier.widthIn(max = 560.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                    ) {
                        introArt?.let { art ->
                            coil3.compose.AsyncImage(
                                model = art,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .width(62.dp)
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                                        .border(1.dp, PlayerChrome.hairline, RoundedCornerShape(ThemeSpec.Card.cornerSm)),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            val label =
                                when {
                                    isLive -> "LIVE"
                                    resumeMs > 0 -> "RESUMING"
                                    else -> "NOW PLAYING"
                                }
                            Text(
                                label,
                                fontSize = ThemeSpec.Type.micro,
                                fontWeight = FontWeight.Bold,
                                color = if (isLive) PlayerChrome.ink else ThemeSemantics.accentBright,
                                letterSpacing = ThemeSpec.Type.trackCaps,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(ThemeSpec.Card.pill))
                                        .background(if (isLive) ThemeSemantics.live.copy(alpha = 0.22f) else PlayerChrome.well)
                                        .padding(horizontal = ThemeSpec.Space.cozy, vertical = 2.dp),
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(ThemeSpec.Space.xs))
                            Text(
                                if (refId.startsWith("live:")) meta.nowTitle.ifBlank { meta.channelName.ifBlank { title } } else title,
                                fontSize = ThemeSpec.Type.titleL,
                                fontWeight = FontWeight.Bold,
                                fontFamily = ThemeSpec.Font.broadcast,
                                color = PlayerChrome.ink,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            val sub =
                                when {
                                    refId.startsWith("live:") -> meta.nextTitle.takeIf { it.isNotBlank() }?.let { "Up next \u00b7 $it" }
                                    meta.subtitle.isNotBlank() -> meta.subtitle
                                    else -> null
                                }
                            sub?.let {
                                Text(
                                    it,
                                    fontSize = ThemeSpec.Type.label,
                                    color = PlayerChrome.inkMuted,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Row(
                                Modifier.padding(top = ThemeSpec.Space.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                            ) {
                                introCert?.let { RatingBadge(it) }
                                if (introTech.isNotBlank()) {
                                    Text(
                                        introTech,
                                        fontSize = ThemeSpec.Type.label,
                                        color = PlayerChrome.inkMuted,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (!refId.startsWith("live:")) {
                                val dur = runCatching { player.duration }.getOrDefault(0L)
                                if (dur > 0) {
                                    Box(
                                        Modifier
                                            .padding(top = ThemeSpec.Space.xs)
                                            .width(320.dp)
                                            .height(3.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(PlayerChrome.track),
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(
                                                    (resumeMs.toFloat() / dur).coerceIn(0f, 1f),
                                                ).fillMaxHeight()
                                                .background(ThemeSemantics.accent),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (cardUp) {
                upNextCountdownMs?.let { remaining ->
                    val ne = nextEp ?: return@let
                    val nextOnline by produceState<com.carteltv.tv.data.stremio.OnlineTitleInfo?>(null, ne.refId) {
                        value =
                            suspendRunCatching {
                                com.carteltv.tv.data.stremio.OnlineTitleInfoLoader
                                    .load(ne.refId)
                            }.getOrNull()
                    }
                    UpNextCard(
                        title = nextOnline?.episodeTitle ?: ne.title,
                        thumbUrl = nextOnline?.episodeThumb,
                        overview = nextOnline?.episodeOverview,
                        secondsLeft = (remaining / 1000 + 1).toInt(),
                        onPlayNow = { playNext(ne) },
                        onCancel = {
                            remote.focusSurface()
                            upNextCancelled = true
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(ThemeSpec.Space.xl),
                    )
                }
            }

            activeSkip?.takeIf { remote.seekTarget == null && remote.deck.value == PlayerControlState.HIDDEN }?.let { seg ->
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(ThemeSpec.Space.xl)
                        .playerGlass(RoundedCornerShape(ThemeSpec.Card.pill), heavy = true, elevation = 18.dp)
                        .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                ) {
                    PlayerGlyphIcon(PlayerGlyph.PLAY, ThemeSemantics.accentBright, 18.dp)
                    Text(
                        seg.kind.label,
                        fontSize = ThemeSpec.Type.meta,
                        fontWeight = FontWeight.SemiBold,
                        color = PlayerChrome.ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text("OK", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, fontFamily = ThemeSpec.Font.mono, maxLines = 1)
                }
            }

            playbackError?.let { errText ->
                PlayerErrorCard(
                    message = errText,
                    detail = playbackDetail,
                    onRetry = {
                        remote.focusSurface()
                        retryPlayback()
                    },
                    onClose = onClosed,
                    retryLabel = if (sessionConflict) "Stop recording and play" else "Retry",
                )
            }
        }
    }
}

private fun savePlaybackProgress(
    refId: String,
    title: String,
    url: String,
    pos: Long,
    dur: Long,
    speed: Float,
) {
    if (refId.startsWith("live:") || refId.isBlank()) return
    AppPreferences.notePlayback(ref = refId, title = title, url = url, poster = "", positionMs = pos, durationMs = if (dur > 0) dur else 0L)
    if (pos > 5_000 && dur > 0 && pos < dur - 15_000) AppPreferences.setTitleResumeMs(refId, pos)
    AppPreferences.setTitleSpeed(refId, speed)
}

/** Tracks chapter completion and persists partial watch time without writing on every tick. */
@Composable
private fun ChapterProgress(
    player: Player,
    refId: String,
    chapters: List<ChapterSkip.Chapter>,
) {
    val context = LocalContext.current

    LaunchedEffect(player, refId, chapters) {
        if (chapters.isEmpty()) return@LaunchedEffect
        val duration = runCatching { player.duration }.getOrDefault(0L)
        if (duration <= 0L) return@LaunchedEffect
        val tracker = ChapterTracker(chapters, duration)
        val session =
            withContext(Dispatchers.IO) {
                ChapterScrobbleStore.beginSession(context, refId, duration, chapters.size)
            }
        session.chapters.forEach { (index, state) -> tracker.restoreCredit(index, state.partialMs) }

        var lastIndex = tracker.indexAt(runCatching { player.currentPosition }.getOrDefault(0L))
        var lastPosition = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
        var pendingMs = 0L
        try {
            while (true) {
                val position = runCatching { player.currentPosition }.getOrDefault(lastPosition).coerceAtLeast(0L)
                val playing = runCatching { player.playWhenReady && player.playbackState == Player.STATE_READY }.getOrDefault(false)
                val delta = if (playing && position >= lastPosition && position - lastPosition <= 5_000L) position - lastPosition else 0L
                val completed = tracker.onTick(position, playing, delta)
                val index = tracker.indexAt(position)
                if (index != lastIndex) {
                    if (lastIndex >= 0 && pendingMs > 0) {
                        withContext(Dispatchers.IO) { ChapterScrobbleStore.accumulate(context, refId, lastIndex, pendingMs) }
                    }
                    pendingMs = 0L
                    lastIndex = index
                }
                if (completed != null) {
                    pendingMs = 0L
                    withContext(Dispatchers.IO) { ChapterScrobbleStore.complete(context, refId, completed) }
                } else if (index >= 0) {
                    pendingMs += delta
                }
                lastPosition = position
                delay(250)
            }
        } finally {
            if (lastIndex >= 0 && pendingMs > 0) {
                withContext(NonCancellable + Dispatchers.IO) {
                    ChapterScrobbleStore.accumulate(context, refId, lastIndex, pendingMs)
                }
            }
        }
    }
}

/** Chapters the file's tracks carry, in file order. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun chaptersOf(player: Player): List<com.carteltv.tv.engine.chapters.ChapterSkip.Chapter> {
    for (group in player.currentTracks.groups) {
        for (i in 0 until group.length) {
            val meta = group.getTrackFormat(i).metadata ?: continue
            for (e in 0 until meta.length()) {
                val entry = meta[e] as? com.carteltv.tv.engine.matroska.ChaptersMetadata ?: continue
                return entry.chapters.map {
                    com.carteltv.tv.engine.chapters.ChapterSkip.Chapter(
                        it.startUs / 1000,
                        it.names.firstOrNull()?.text,
                    )
                }
            }
        }
    }
    return emptyList()
}
