@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.youtube
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.settings.QrCode
import com.carteltv.tv.ui.theme.ThemeSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import coil3.compose.AsyncImage
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.youtube.YouTubeCategoryPresets
import com.carteltv.tv.data.youtube.YouTubeChapters
import com.carteltv.tv.data.youtube.YouTubeDataSource
import com.carteltv.tv.data.youtube.YouTubeDislikes
import com.carteltv.tv.data.youtube.YouTubeFeedFilter
import com.carteltv.tv.data.youtube.YouTubeApiClient
import com.carteltv.tv.data.youtube.YouTubeLibrary
import com.carteltv.tv.data.youtube.YouTubeSegmentPlan
import com.carteltv.tv.data.youtube.YouTubeSubscriptions
import com.carteltv.tv.engine.stability.PlaybackFailure
import com.carteltv.tv.ui.player.PlayerGlyph
import com.carteltv.tv.ui.player.PlayerGlyphIcon
import com.carteltv.tv.ui.player.PlayerControlState
import com.carteltv.tv.ui.player.DialogueBoost
import com.carteltv.tv.ui.player.PlayerErrorCard
import com.carteltv.tv.ui.player.PlayerChrome
import com.carteltv.tv.ui.player.PlayerHandover
import com.carteltv.tv.ui.player.PlayerMeta
import com.carteltv.tv.ui.player.PlayerOverlay
import com.carteltv.tv.ui.player.PlayerRemote
import com.carteltv.tv.ui.player.PlayerVideoView
import com.carteltv.tv.ui.player.PlaybackStallGuard
import com.carteltv.tv.ui.player.ReportNowPlaying
import com.carteltv.tv.ui.player.UpNextCard
import com.carteltv.tv.ui.player.buildCartelPlayer
import com.carteltv.tv.ui.player.cartelExtractors
import com.carteltv.tv.ui.player.playerSurface
import com.carteltv.tv.ui.player.rememberPlayerRemote
import com.carteltv.tv.ui.player.rememberVideoGeometry
import com.carteltv.tv.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.home.ShelfEntry
import com.carteltv.tv.ui.components.CardArtwork
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.trapFocus

/* CARTEL · YOUTUBE player view — the mirror app's fullscreen playback surface
   and player chrome; the screen-level banner lives in YouTubeScreen.kt. */

/** Aspect and subtitle size are kept for the whole section: YouTube titles are short and unrelated. */
internal const val VIDEO_GEOMETRY_KEY = "yt"

/** How often the player is asked whether it is getting anywhere. */
internal const val WATCH_POLL_MS = 500L

@Composable
internal fun YouTubeEmbeddedPlayer(
    videoId: String,
    streams: YouTubeApiClient.YouTubeStreams,
    next: YouTubeApiClient.YouTubeVideo?,
    channel: String? = null,
    channelId: String? = null,
    publishedMs: Long = 0L,
    queue: List<YouTubeApiClient.YouTubeVideo> = emptyList(),
    onClosed: () -> Unit,
    onEnded: () -> Unit,
    onPlayNext: () -> Unit,
) {
    // Renderers are fixed when a player is built, so a Voice boost change swaps the player. What is
    // playing, and whether the deck is up, outlive the swap.
    var handover by remember(videoId) { mutableStateOf<PlayerHandover?>(null) }
    key(videoId) {
        val deck = remember { PlayerControlState() }
        key(handover) {
            YouTubePlayer(videoId, streams, next, channel, channelId, publishedMs, queue, handover, deck, onClosed, onEnded, onPlayNext) { handover = it }
        }
    }
}

@Composable
internal fun YouTubePlayer(
    videoId: String,
    streams: YouTubeApiClient.YouTubeStreams,
    next: YouTubeApiClient.YouTubeVideo?,
    channel: String?,
    channelId: String?,
    publishedMs: Long,
    queue: List<YouTubeApiClient.YouTubeVideo>,
    handover: PlayerHandover?,
    deck: PlayerControlState,
    onClosed: () -> Unit,
    onEnded: () -> Unit,
    onPlayNext: () -> Unit,
    onRebuild: (PlayerHandover) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var activeStreams by remember(videoId) { mutableStateOf(streams) }
    var playbackError by remember(videoId) { mutableStateOf<String?>(null) }
    var playbackDetail by remember(videoId) { mutableStateOf<String?>(null) }
    var recovering by remember(videoId) { mutableStateOf(false) }
    var autoRecoverCount by remember(videoId) { mutableStateOf(0) }
    var pendingSeekMs by remember(videoId) {
        mutableStateOf(if (streams.isLive) 0L else handover?.positionMs ?: AppPreferences.titleResumeMs("yt:$videoId"))
    }
    var lastFailedUrl by remember(videoId) { mutableStateOf<String?>(null) }
    var autoCancelled by remember(videoId) { mutableStateOf(false) }

    fun youTubeHttp(s: YouTubeApiClient.YouTubeStreams) = YouTubeDataSource.Factory(
        fallbackUserAgent = s.userAgent,
        onForbidden = { url, _ -> lastFailedUrl = url },
    )

    val extractors = remember(videoId) { cartelExtractors(context) }
    // The shared recipe, with YouTube's own data source and the stream's live flag for its buffers.
    val player = remember(videoId) {
        buildCartelPlayer(
            context,
            live = streams.isLive,
            sourceFactory = DefaultMediaSourceFactory(youTubeHttp(streams), extractors),
        ).build()
    }
    val watchdog = remember(videoId) { PlaybackStallGuard() }

    fun loadStreams(s: YouTubeApiClient.YouTubeStreams, seekMs: Long) {
        val http = youTubeHttp(s)
        val vSource = s.videoUrl?.let {
            ProgressiveMediaSource.Factory(http, extractors).createMediaSource(MediaItem.fromUri(it))
        }
        val aSource = s.audioUrl?.let {
            ProgressiveMediaSource.Factory(http, extractors).createMediaSource(MediaItem.fromUri(it))
        }
        when {
            s.isLive && !s.hlsUrl.isNullOrBlank() -> {
                val hlsItem = MediaItem.Builder()
                    .setUri(s.hlsUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                player.setMediaSource(HlsMediaSource.Factory(http).createMediaSource(hlsItem))
            }
            vSource != null && aSource != null ->
                player.setMediaSource(MergingMediaSource(vSource, aSource))
            !s.hlsUrl.isNullOrBlank() -> {
                val hlsItem = MediaItem.Builder()
                    .setUri(s.hlsUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                player.setMediaSource(HlsMediaSource.Factory(http).createMediaSource(hlsItem))
            }
            vSource != null -> player.setMediaSource(vSource)
            else -> {
                val fallbackUri = s.muxedUrl ?: s.videoUrl.orEmpty()
                player.setMediaSource(ProgressiveMediaSource.Factory(http, extractors).createMediaSource(MediaItem.fromUri(fallbackUri)))
            }
        }
        watchdog.attemptStarted(android.os.SystemClock.elapsedRealtime())
        player.prepare()
        if (!s.isLive && seekMs > 3_000L) {
            com.carteltv.tv.engine.stability.BackgroundWorkScheduler.onSeek()
            player.seekTo(seekMs)
        }
        // A rebuilt player resumes only if the old one was running.
        player.playWhenReady = handover?.playing ?: true
    }

    fun reResolveAndResume(forceFresh: Boolean, fallbackErr: String? = null, fallbackDetail: String? = null) {
        if (recovering) return
        val resumeAt = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(pendingSeekMs)
        recovering = true
        playbackError = null
        playbackDetail = null
        scope.launch {
            lastFailedUrl?.let { YouTubeApiClient.reportStreamDenied(it) }
            val fresh = YouTubeApiClient.resolve(videoId, forceFresh = forceFresh)
            recovering = false
            if (fresh != null && (fresh.videoUrl != null || fresh.muxedUrl != null || fresh.hlsUrl != null)) {
                pendingSeekMs = resumeAt
                activeStreams = fresh.copy(title = activeStreams.title.ifBlank { fresh.title })
            } else {
                playbackError = fallbackErr ?: "No working stream could be found."
                playbackDetail = fallbackDetail
            }
        }
    }

    LaunchedEffect(activeStreams) {
        loadStreams(activeStreams, pendingSeekMs)
    }

    DisposableEffect(player) {
        com.carteltv.tv.engine.stability.BackgroundWorkScheduler.enterPlayback()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    playbackError = null
                    pendingSeekMs = 0L
                }
                if (playbackState == Player.STATE_ENDED) {
                    // Auto-advance follows the Up Next setting, as the main player does.
                    if (autoCancelled || AppPreferences.upNextMode == "off") onClosed() else onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("CartelTube", "Player error [${error.errorCodeName}]: ${error.message}", error)
                val isHttpOrIo = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                if (isHttpOrIo && autoRecoverCount < 2) {
                    autoRecoverCount++
                    val failedUrl = lastFailedUrl ?: activeStreams.videoUrl ?: activeStreams.audioUrl
                    if (failedUrl != null) YouTubeApiClient.reportStreamDenied(failedUrl)
                    android.util.Log.w("CartelTube", "Auto-recovering $videoId attempt $autoRecoverCount after ${error.errorCodeName}")
                    reResolveAndResume(forceFresh = autoRecoverCount >= 2, fallbackErr = PlaybackFailure.userMessage(error), fallbackDetail = error.errorCodeName)
                } else {
                    playbackError = PlaybackFailure.userMessage(error)
                    playbackDetail = error.errorCodeName
                }
            }
        }
        player.addListener(listener)
        onDispose {
            com.carteltv.tv.engine.stability.BackgroundWorkScheduler.exitPlayback()
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            val dur = runCatching { player.duration }.getOrDefault(0L)
            if (!activeStreams.isLive && pos > 5_000L && dur > 0L && pos < dur - 15_000L) {
                AppPreferences.setTitleResumeMs("yt:$videoId", pos)
            } else if (!activeStreams.isLive && dur > 0L && pos >= dur - 15_000L) {
                AppPreferences.setTitleResumeMs("yt:$videoId", 0L)
            }
            player.removeListener(listener)
            player.release()
        }
    }

    // A stream that never starts, or stops feeding, ends at the error card instead of a spinner.
    LaunchedEffect(player, watchdog) {
        while (true) {
            delay(WATCH_POLL_MS)
            when (val outcome = watchdog.check(android.os.SystemClock.elapsedRealtime(), player.playbackState, player.playWhenReady)) {
                PlaybackStallGuard.Outcome.None -> Unit
                PlaybackStallGuard.Outcome.Retry -> {
                    android.util.Log.w("CartelTube", "Reloading $videoId: the player stopped making progress")
                    player.stop()
                    player.prepare()
                }
                is PlaybackStallGuard.Outcome.GiveUp -> {
                    player.stop()
                    playbackError = when (outcome.kind) {
                        PlaybackStallGuard.Kind.STARTUP -> "The video did not start. Wait a moment, then retry."
                        PlaybackStallGuard.Kind.STALL -> "The video stopped playing. Wait a moment, then retry."
                    }
                    playbackDetail = null
                }
            }
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        var pausedByStop = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    pausedByStop = player.playWhenReady
                    runCatching { player.pause() }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (pausedByStop) {
                    pausedByStop = false
                    runCatching { player.play() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { onClosed() }

    /* publish playback state for the sleep timer */
    ReportNowPlaying(player, activeStreams.title.ifBlank { "YouTube" }, channel)

    // Up-next countdown: shown for the last seconds of a video when another is queued behind it.
    var secsLeft by remember(videoId) { mutableStateOf(-1) }
    LaunchedEffect(player, next) {
        while (true) {
            val dur = runCatching { player.duration }.getOrDefault(0L)
            val pos = runCatching { player.currentPosition }.getOrDefault(0L)
            secsLeft = if (next != null && !activeStreams.isLive && dur > 0L && dur - pos in 0L..UP_NEXT_MS) ((dur - pos) / 1000L).toInt() + 1 else -1
            delay(500)
        }
    }

    // SponsorBlock segment sweeper polling player position at 250 ms intervals.
    var sweepNote by remember(videoId) { mutableStateOf<String?>(null) }
    var muteSeg by remember(videoId) { mutableStateOf<YouTubeApiClient.Segment?>(null) }
    LaunchedEffect(sweepNote) {
        if (sweepNote != null) {
            delay(3_500L)
            sweepNote = null
        }
    }
    LaunchedEffect(videoId) {
        val segments = if (AppPreferences.youTubeSponsorBlock && !activeStreams.isLive) {
            withContext(Dispatchers.IO) {
                YouTubeApiClient.sponsorSegments(videoId, YouTubeCategoryPresets.cats(AppPreferences.youTubeCategoryPreset))
            }
        } else {
            emptyList()
        }
        val plan = YouTubeSegmentPlan(segments)
        if (!plan.isEmpty) sweepNote = "${plan.count} segments"
        while (!plan.isEmpty) {
            val t = player.currentPosition / 1000.0
            if (player.isPlaying) {
                plan.step(t).forEach { d ->
                    when (d) {
                        is YouTubeSegmentPlan.Decision.Seek -> {
                            player.seekTo((d.toSec * 1000).toLong())
                            sweepNote = "Skipped ${YouTubeApiClient.label(d.segment.category)} · ${(d.segment.endSec - d.segment.startSec).toInt()}s"
                        }
                        is YouTubeSegmentPlan.Decision.Mute -> {
                            muteSeg = d.segment
                            if (d.entered) sweepNote = "Muted ${d.segment?.let { YouTubeApiClient.label(it.category) }.orEmpty()}"
                        }
                        YouTubeSegmentPlan.Decision.None -> Unit
                    }
                }
            }
            delay(plan.nextDelayMs(t))
        }
    }
    LaunchedEffect(muteSeg) { player.volume = if (muteSeg != null) 0f else 1f }
    LaunchedEffect(videoId) { player.setPlaybackSpeed(if (activeStreams.isLive) 1f else AppPreferences.youTubeSpeed) }

    var infoNote by remember(videoId) { mutableStateOf<String?>(null) }
    var votes by remember(videoId) { mutableStateOf<YouTubeDislikes.Votes?>(null) }
    var channelAvatar by remember(videoId) { mutableStateOf<String?>(null) }
    var commentsOpen by remember(videoId) { mutableStateOf(false) }
    var comments by remember(videoId) { mutableStateOf<List<YouTubeApiClient.YouTubeComment>>(emptyList()) }
    var chapters by remember(videoId) { mutableStateOf<List<YouTubeChapters.Chapter>>(emptyList()) }
    LaunchedEffect(infoNote) {
        if (infoNote != null) {
            delay(5_000L)
            infoNote = null
        }
    }
    LaunchedEffect(videoId) {
        if (AppPreferences.youTubeDislikes) YouTubeDislikes.votes(videoId)?.let { votes = it; infoNote = it.label }
    }
    LaunchedEffect(channelId, activeStreams.channelId) {
        val id = channelId ?: activeStreams.channelId ?: return@LaunchedEffect
        channelAvatar = withContext(Dispatchers.IO) { YouTubeApiClient.channel(id)?.avatar }
    }
    LaunchedEffect(videoId, commentsOpen) {
        if (commentsOpen && comments.isEmpty()) {
            comments = withContext(Dispatchers.IO) { YouTubeApiClient.comments(videoId) }
        }
    }
    LaunchedEffect(videoId) {
        if (activeStreams.isLive) return@LaunchedEffect
        chapters = YouTubeApiClient.chapters(videoId)
        var last = -1
        while (chapters.isNotEmpty()) {
            val i = YouTubeChapters.indexAt(chapters, player.currentPosition)
            if (i != last) {
                if (i >= 0 && last != -1) sweepNote = "Chapter · ${chapters[i].title}"
                last = i
            }
            delay(1_000)
        }
    }

    /** Jumps one chapter in [direction]; false at the ends, so the caller may go to the next video instead. */
    fun jumpChapter(direction: Int): Boolean {
        if (chapters.isEmpty()) return false
        val i = YouTubeChapters.indexAt(chapters, player.currentPosition)
        val target = if (direction > 0) chapters.getOrNull(i + 1)
        else if (player.currentPosition - chapters.getOrNull(i)?.startMs.let { it ?: 0L } > 3_000L) chapters.getOrNull(i)
        else chapters.getOrNull(i - 1) ?: chapters.first()
        target?.let { player.seekTo(it.startMs); sweepNote = "Chapter · ${it.title}" }
        return target != null
    }

    /* ── the remote: every key that no focused control uses ── */
    lateinit var remote: PlayerRemote
    remote = rememberPlayerRemote(
        player = player,
        live = { activeStreams.isLive },
        deck = deck,
        onClose = onClosed,
    ) {
        // Next is the next chapter while there is one, then the next video of the queue.
        onNext = { if (!jumpChapter(1) && next != null) onPlayNext() }
        upNextVisible = { next != null && secsLeft > 0 && !autoCancelled && remote.seekTarget == null && deck.value == PlayerControlState.HIDDEN && playbackError == null }
        onCancelUpNext = { autoCancelled = true }
        errorUp = { playbackError != null }
    }
    // On screen only over bare video, and never when the viewer turned Up Next off.
    val cardUp = AppPreferences.upNextMode != "off" && remote.host.upNextVisible()
    val geometry = rememberVideoGeometry(VIDEO_GEOMETRY_KEY)

    // Match the TV's refresh rate to the video's frame rate; 24p film on a 60 Hz panel judders
    // (measured: presents alternating 33/50 ms). The surface arrives via the view, the rate via
    // the first size change, so both are read at event time, not composition time.
    var videoHost by remember { mutableStateOf<androidx.media3.ui.PlayerView?>(null) }
    DisposableEffect(player) {
        val syncListener = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                // VideoSize carries no rate in this media3 version; the track format does.
                val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: return
                val surface = (videoHost?.videoSurfaceView as? android.view.SurfaceView)?.holder?.surface
                com.carteltv.tv.engine.playback.RefreshRateSync.apply(context, surface, fps)
            }
        }
        player.addListener(syncListener)
        onDispose {
            player.removeListener(syncListener)
            if (AppPreferences.refreshSync) com.carteltv.tv.engine.playback.RefreshRateSync.restore(context)
        }
    }

    Box(
        Modifier.fillMaxSize().background(PlayerChrome.floor).playerSurface(remote),
    ) {
        PlayerVideoView(player, geometry, Modifier.fillMaxSize(), onView = { videoHost = it })

        val related = remember(queue, next, videoId) { listOfNotNull(next) + queue.filter { it.videoId != videoId && it.videoId != next?.videoId } }
        PlayerOverlay(
            player = player,
            meta = PlayerMeta(
                title = activeStreams.title.ifBlank { "YouTube" },
                subtitle = listOfNotNull(channel, if (activeStreams.isLive) "Live" else null).joinToString(" · "),
                isLive = activeStreams.isLive,
                refId = "yt:$videoId",
                streamInfo = activeStreams.classifications,
            ),
            remote = remote,
            onPlayNext = next?.let { { onPlayNext() } },
            geometry = geometry,
            onSpeedChange = { AppPreferences.youTubeSpeed = it },
            onVoiceBoostChange = {
                AppPreferences.dialogueBoost = (AppPreferences.dialogueBoost + 1) % DialogueBoost.LEVELS
                onRebuild(PlayerHandover(player.currentPosition, player.playWhenReady))
            },
            onToggleComments = { commentsOpen = !commentsOpen },
            commentsActive = commentsOpen,
            extraSidePanelOpen = commentsOpen,
            onDismissExtraSidePanel = { commentsOpen = false },
            extraSidePanel = { dismiss ->
                YouTubeCommentsPanel(comments = comments, onDismiss = dismiss)
            },
            overlayContent = {
                YouTubeConceptPanels(
                    visible = deck.value != PlayerControlState.HIDDEN && !commentsOpen,
                    title = activeStreams.title.ifBlank { "YouTube" },
                    channel = channel,
                    channelAvatar = channelAvatar,
                    publishedMs = publishedMs,
                    publishedAt = activeStreams.publishedAt,
                    viewCount = activeStreams.viewCount,
                    isLive = activeStreams.isLive,
                    votes = votes,
                    next = next,
                    related = related.take(3),
                    onSelectRelated = { v -> if (v.videoId == next?.videoId) onPlayNext() },
                )
            },
        )

        val note = sweepNote ?: infoNote
        if (note != null) Box(
            Modifier.align(Alignment.TopStart).padding(top = NoteInsetTop, start = NoteInsetStart)
                .background(PlayerChrome.chip, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .border(1.dp, YouTubeBrass.copy(alpha = 0.55f), RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
        ) {
            Text(note, fontSize = ThemeSpec.Type.meta, color = PlayerChrome.ink)
        }
        if (cardUp) UpNextCard(
            title = next?.title.orEmpty(),
            thumbUrl = next?.thumb,
            subtitle = next?.channel,
            secondsLeft = secsLeft,
            onPlayNow = onPlayNext,
            onCancel = { remote.focusSurface(); autoCancelled = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(ThemeSpec.Space.xl),
        )
        if (recovering && playbackError == null) Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = RecoverNoticeInsetBottom)
                .background(PlayerChrome.chip, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .border(1.dp, YouTubeBrass.copy(alpha = 0.55f), RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
        ) {
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.youtube_switching_angle), fontSize = ThemeSpec.Type.meta, color = PlayerChrome.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        playbackError?.let { errText ->
            PlayerErrorCard(
                message = errText,
                detail = playbackDetail,
                onRetry = {
                    autoRecoverCount = 0
                    watchdog.reset()
                    remote.focusSurface()
                    reResolveAndResume(forceFresh = true, fallbackErr = errText, fallbackDetail = playbackDetail)
                },
                onClose = onClosed,
            )
        }
    }
}
