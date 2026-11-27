package com.carteltv.tv.ui.youtube
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.youtube.YouTubeApiClient
import com.carteltv.tv.data.youtube.YouTubeCategoryPresets
import com.carteltv.tv.data.youtube.YouTubeChapters
import com.carteltv.tv.data.youtube.YouTubeDataSource
import com.carteltv.tv.data.youtube.YouTubeDislikes
import com.carteltv.tv.data.youtube.YouTubeFeedFilter
import com.carteltv.tv.data.youtube.YouTubeLibrary
import com.carteltv.tv.data.youtube.YouTubeSegmentPlan
import com.carteltv.tv.data.youtube.YouTubeSubscriptions
import com.carteltv.tv.engine.stability.PlaybackFailure
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.player.DialogueBoost
import com.carteltv.tv.ui.player.PlaybackStallGuard
import com.carteltv.tv.ui.player.PlayerControlState
import com.carteltv.tv.ui.player.PlayerErrorCard
import com.carteltv.tv.ui.player.PlayerGlyph
import com.carteltv.tv.ui.player.PlayerHandover
import com.carteltv.tv.ui.player.PlayerMeta
import com.carteltv.tv.ui.player.PlayerOverlay
import com.carteltv.tv.ui.player.PlayerRemote
import com.carteltv.tv.ui.player.PlayerVideoView
import com.carteltv.tv.ui.player.ReportNowPlaying
import com.carteltv.tv.ui.player.UpNextCard
import com.carteltv.tv.ui.player.buildCartelPlayer
import com.carteltv.tv.ui.player.cartelExtractors
import com.carteltv.tv.ui.player.playerSurface
import com.carteltv.tv.ui.player.rememberPlayerRemote
import com.carteltv.tv.ui.player.rememberVideoGeometry
import com.carteltv.tv.ui.settings.QrCode
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CARTEL · YOUTUBE — visual mirror of YouTube's TV app.
 *
 * Look: the leanback dialect — near-black stage, left rail, 16:9 shelves with
 * duration badges, red accents.
 * Innertube spoken directly as ANDROID_VR (browse FEwhat_to_watch → YouTube's
 * own "Recommended"/topic shelves; search → results; player → direct URLs),
 * our ExoPlayer with A/V-merged adaptive tracks, SponsorBlock swept by our
 * own player clock. Zero YouTube web UI; zero ad structures ever requested.
 */

internal val YouTubeBg: Color get() = ThemeSemantics.stage

/** The house red, so the mirror reads in the same accent as every other screen. */
internal val YouTubeAccent: Color get() = ThemeSemantics.accent
internal val YouTubeDim = ThemeSemantics.textSecondary
internal val YouTubeCardIdle: Color get() = ThemeSemantics.surfaceRest

// cartel materials riding inside the mirror
internal val YouTubeBrass: Color get() = ThemeSemantics.accent

internal const val UP_NEXT_MS = 12_000L

/**
 * Floating notes (chapter skips, the dislike count) sit top-left. These clear player chrome rather than follow the
 * page rhythm, so they stay literal: 108dp is four overscan margins (27dp) down, and 64dp lines a note up with the
 * player deck's own text inset.
 */
internal val NoteInsetTop = 108.dp
internal val NoteInsetStart = 64.dp

/** The recovering notice rests just above the deck's bottom-padding line (Space.xl, 32dp). */
internal val RecoverNoticeInsetBottom = 34.dp

/** Shelves built from the viewer's own activity, shown above YouTube's. */
private fun localShelves(subFeed: List<YouTubeApiClient.YouTubeVideo>): List<YouTubeApiClient.YouTubeShelf> =
    buildList {
        val resumable = YouTubeLibrary.history().filter { AppPreferences.titleResumeMs("yt:${it.videoId}") > 5_000L }.take(12)
        if (resumable.isNotEmpty()) {
            add(
                YouTubeApiClient.YouTubeShelf(com.carteltv.tv.data.provider.ChannelRepository.WATCH_NEXT_TITLE, resumable),
            )
        }
        if (subFeed.isNotEmpty()) add(YouTubeApiClient.YouTubeShelf("From your subscriptions", subFeed.take(16)))
        val saved = YouTubeLibrary.saved().take(12)
        if (saved.isNotEmpty()) add(YouTubeApiClient.YouTubeShelf("Saved for later", saved))
    }

/** Which rail row a view belongs to: search has a row of its own. */
private fun selectedTabFor(view: String): String =
    when (view) {
        "TRENDING" -> "Trending"
        "SUBS", "CHANNEL" -> "Subscriptions"
        "LIBRARY", "SAVED" -> "Library"
        "HISTORY" -> "History"
        "SETTINGS" -> "Settings"
        "SEARCH" -> "Search"
        else -> "Home"
    }

@Composable
fun YouTubeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var view by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf("HOME") }
    var query by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf("") }
    var shelves by remember { mutableStateOf<List<YouTubeApiClient.YouTubeShelf>>(emptyList()) }
    var results by remember { mutableStateOf<List<YouTubeApiClient.YouTubeVideo>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<YouTubeApiClient.YouTubeVideo?>(null) }
    var playingStreams by remember { mutableStateOf<YouTubeApiClient.YouTubeStreams?>(null) }
    var queue by remember { mutableStateOf<List<YouTubeApiClient.YouTubeVideo>>(emptyList()) }
    var restoreVideoId by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    var restoreQueueIds by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var libTick by remember { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<YouTubeApiClient.YouTubeVideo?>(null) }
    var prevView by remember { mutableStateOf("HOME") }
    var subs by remember { mutableStateOf(YouTubeSubscriptions.all()) }
    var subFeed by remember { mutableStateOf<List<YouTubeApiClient.YouTubeVideo>>(emptyList()) }
    val unseenSubCount =
        remember(subFeed, libTick) {
            subFeed.count {
                it.publishedMs > AppPreferences.youTubeSubSeenMs &&
                    it.publishedMs > 0L
            }
        }
    LaunchedEffect(view, subFeed) {
        if (view == "SUBS") {
            val newest = subFeed.maxOfOrNull { it.publishedMs } ?: 0L
            if (newest > AppPreferences.youTubeSubSeenMs) AppPreferences.youTubeSubSeenMs = newest
        }
    }
    var subBusy by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf<YouTubeApiClient.YouTubeChannel?>(null) }
    var channelBusy by remember { mutableStateOf(false) }
    var channelNotice by remember { mutableStateOf<String?>(null) }
    // Account playlists (Liked videos, Watch later) — loaded only while signed in.
    var accountShelves by remember { mutableStateOf<List<YouTubeApiClient.YouTubeShelf>>(emptyList()) }
    // device-flow login state: START → code handed out → polling → signed in
    var login by remember { mutableStateOf<String?>(null) }
    // Signing in or out re-keys the account feeds and the rail's account row.
    var sessionTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // Fullscreen immersion while a YouTube video is playing: keep the screen on
    // and hide the system bars so the player owns the whole display.
    DisposableEffect(playing) {
        val activity = context as? android.app.Activity
        val window = activity?.window
        if (playing != null) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (playing != null) {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun loadFeed() {
        busy = true
        notice = null
        scope.launch {
            shelves = YouTubeFeedFilter.shelves(YouTubeApiClient.homeFeed())
            busy = false
            if (shelves.isEmpty()) notice = "Couldn't load videos. Check the connection, then press Retry."
        }
    }

    LaunchedEffect(Unit) {
        loadFeed()
        scope.launch(Dispatchers.IO) { YouTubeApiClient.preWarmPipePipe() }
    }

    fun doSearch(q: String) {
        if (q.isBlank()) return
        query = q
        busy = true
        notice = null
        view = "SEARCH"
        YouTubeLibrary.noteSearch(q)
        libTick++
        scope.launch {
            val found = YouTubeFeedFilter.videos(YouTubeApiClient.search(q.trim()))
            // A faster later search may already have answered; only the current term may show.
            if (query.trim() != q.trim()) return@launch
            results = found
            busy = false
            if (results.isEmpty()) notice = "No results for \"$q\""
        }
    }

    fun openSearch() {
        if (view != "SEARCH") prevView = view
        view = "SEARCH"
        notice = null
    }

    fun play(
        v: YouTubeApiClient.YouTubeVideo,
        list: List<YouTubeApiClient.YouTubeVideo>,
    ) {
        resolving = true
        notice = null
        queue = list.ifEmpty { listOf(v) }
        restoreVideoId = v.videoId
        restoreQueueIds = queue.map { it.videoId }
        scope.launch {
            // Resolve streams first; the better title/thumbnail arrives afterwards and never delays playback.
            val s = YouTubeApiClient.resolve(v.videoId)
            resolving = false
            if (s == null || (s.videoUrl == null && s.muxedUrl == null && s.hlsUrl == null)) {
                notice = "That video isn't available right now."
                playing = null
                playingStreams = null
            } else {
                playingStreams = s.copy(title = v.title)
                playing = v
                YouTubeLibrary.noteWatched(v)
                libTick++
                scope.launch {
                    val de = withTimeoutOrNull(1_500L) { YouTubeApiClient.deArrow(v.videoId) }
                    if (de != null && playing?.videoId == v.videoId) {
                        playing =
                            v.copy(
                                title = de.first.ifBlank { v.title },
                                thumb = de.second.ifBlank { v.thumb ?: "" }.ifBlank { null },
                            )
                        playingStreams = playingStreams?.copy(title = de.first.ifBlank { v.title })
                    }
                }
            }
        }
    }

    fun closePlayer() {
        playing = null
        playingStreams = null
        restoreVideoId = null
        restoreQueueIds = emptyList()
    }

    fun playNextOrClose() {
        val idx = queue.indexOfFirst { it.videoId == playing?.videoId }
        val next = if (idx >= 0) queue.getOrNull(idx + 1) else null
        if (next != null) play(next, queue) else closePlayer()
    }

    fun toggleSaved(v: YouTubeApiClient.YouTubeVideo) {
        YouTubeLibrary.toggleSaved(v)
        libTick++
    }

    fun refreshSubs(force: Boolean) {
        subs = YouTubeSubscriptions.all()
        subBusy = true
        scope.launch {
            subFeed = YouTubeSubscriptions.feed(force)
            subBusy = false
        }
    }

    /** The rail's account row: signed out it opens the device sign-in, signed in it signs straight out. */
    fun accountAction() {
        if (com.carteltv.tv.data.youtube.YouTubeSession
                .signedIn()
        ) {
            com.carteltv.tv.data.youtube.YouTubeSession
                .signOut()
            accountShelves = emptyList()
            results = emptyList()
            sessionTick++
            com.carteltv.tv.ui.components.TransientStatus
                .show("Signed out of YouTube.")
            refreshSubs(true)
        } else {
            login = "START"
        }
    }
    LaunchedEffect(Unit) { refreshSubs(false) }
    LaunchedEffect(Unit) {
        val request = YouTubeDeepLink.take()
        if (request != null) {
            val video = YouTubeApiClient.YouTubeVideo(request.videoId, request.title, channel = null, duration = null, thumb = null)
            play(video, listOf(video))
            return@LaunchedEffect
        }
        val id = restoreVideoId ?: return@LaunchedEffect
        val history = YouTubeLibrary.history()
        val restoredQueue = restoreQueueIds.mapNotNull { videoId -> history.firstOrNull { it.videoId == videoId } }
        val video =
            history.firstOrNull { it.videoId == id }
                ?: YouTubeApiClient.YouTubeVideo(id, "YouTube video", channel = null, duration = null, thumb = null)
        play(video, restoredQueue.ifEmpty { listOf(video) })
    }
    LaunchedEffect(login, sessionTick) {
        if (!com.carteltv.tv.data.youtube.YouTubeSession
                .signedIn()
        ) {
            accountShelves = emptyList()
            return@LaunchedEffect
        }
        accountShelves =
            buildList {
                com.carteltv.tv.core
                    .suspendRunCatching { YouTubeApiClient.likedVideos() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(YouTubeApiClient.YouTubeShelf("Liked videos", it.take(24))) }
                com.carteltv.tv.core
                    .suspendRunCatching { YouTubeApiClient.watchLater() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(YouTubeApiClient.YouTubeShelf("Watch later", it.take(24))) }
            }
    }

    fun openChannel(
        id: String,
        name: String,
    ) {
        if (view != "CHANNEL") prevView = view
        view = "CHANNEL"
        channel = null
        channelNotice = null
        channelBusy = true
        scope.launch {
            val c = YouTubeApiClient.channel(id)
            channelBusy = false
            if (c == null) channelNotice = "Couldn't load $name. Check the connection and try again." else channel = c
        }
    }

    fun toggleSub(sub: YouTubeSubscriptions.Sub) {
        YouTubeSubscriptions.toggle(sub)
        libTick++
        refreshSubs(false)
    }

    BackHandler(enabled = playing != null || view != "HOME") {
        when {
            playing != null -> closePlayer()
            else -> view = if (view == "CHANNEL" || view == "SEARCH") prevView else "HOME"
        }
    }

    val streams = playingStreams
    val current = playing
    // YouTube is a full-surface app: the global rail steps aside for its own section rail.
    com.carteltv.tv.ui.nav
        .HideBarWhile()
    Box(Modifier.fillMaxSize().background(YouTubeBg)) {
        if (streams != null && current != null) {
            // Full-screen playback
            YouTubeEmbeddedPlayer(
                videoId = current.videoId,
                streams = streams,
                next =
                    run {
                        val at = queue.indexOfFirst { it.videoId == current.videoId }
                        if (at >= 0) queue.getOrNull(at + 1) else null
                    },
                channel = current.channel,
                channelId = current.channelId,
                publishedMs = current.publishedMs,
                queue = queue,
                onOpenQueue = if (queue.isNotEmpty()) ({}) else null,
                onClosed = ::closePlayer,
                onEnded = ::playNextOrClose,
                onPlayNext = ::playNextOrClose,
            )
        } else {
            val signedIn =
                remember(login, sessionTick) {
                    com.carteltv.tv.data.youtube.YouTubeSession
                        .signedIn()
                }
            val selectedTab = selectedTabFor(view)
            // The one frame every section uses: masthead with the title and clock, then the section rail and
            // stage on the shared safe margins. Search and the account live in the rail, like every other app.
            ScreenPage("YouTube", icon = com.carteltv.tv.R.drawable.ph_youtube_logo, header = false) {
                Row(Modifier.fillMaxSize()) {
                    YouTubeRail(
                        selected = selectedTab,
                        signedIn = signedIn,
                        unseenSubCount = unseenSubCount,
                        onSelect = { label ->
                            when (label) {
                                "Search" -> {
                                    openSearch()
                                }

                                "Home" -> {
                                    view = "HOME"
                                    if (shelves.isEmpty() && !busy) loadFeed()
                                }

                                "Trending" -> {
                                    view = "TRENDING"
                                    if (shelves.isEmpty() && !busy) loadFeed()
                                }

                                "Subscriptions" -> {
                                    view = "SUBS"
                                    refreshSubs(false)
                                }

                                "Library" -> {
                                    view = "LIBRARY"
                                }

                                "History" -> {
                                    view = "HISTORY"
                                }

                                "Settings" -> {
                                    view = "SETTINGS"
                                }
                            }
                        },
                        onAccount = ::accountAction,
                    )
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            YouTubeStage(
                                view = view,
                                shelves = shelves,
                                extraShelves = remember(libTick, subFeed, accountShelves) { accountShelves + localShelves(subFeed) },
                                results = results,
                                query = query,
                                busy = busy,
                                notice = notice,
                                libTick = libTick,
                                subs = subs,
                                subFeed = subFeed,
                                subBusy = subBusy,
                                channel = channel,
                                channelBusy = channelBusy,
                                channelNotice = channelNotice,
                                onQuery = {
                                    query = it
                                    if (it.isBlank()) {
                                        results = emptyList()
                                        busy = false
                                    }
                                },
                                onSearch = { doSearch(query) },
                                onRecentSearch = ::doSearch,
                                onRetryFeed = ::loadFeed,
                                onClearHistory = {
                                    YouTubeLibrary.clearHistory()
                                    libTick++
                                },
                                onPlay = ::play,
                                onMenu = { menuFor = it },
                                onToggleSaved = ::toggleSaved,
                                onOpenChannel = ::openChannel,
                                onToggleSub = ::toggleSub,
                                onRefreshSubs = { refreshSubs(true) },
                            )
                        }
                    }
                }
            }
        }

        if (resolving) {
            Box(
                Modifier.fillMaxSize().background(ThemeSemantics.scrimBase.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { LoadingSpinner() }
        }

        menuFor?.let { v ->
            YouTubeMenu(
                v,
                view,
                subscribed = YouTubeSubscriptions.isSubscribed(v.channelId),
                onDismiss = { menuFor = null },
                onPlay = {
                    menuFor = null
                    play(v, listOf(v))
                },
                onToggleSaved = {
                    toggleSaved(v)
                    menuFor = null
                },
                onRemoveHistory = {
                    YouTubeLibrary.removeFromHistory(v.videoId)
                    libTick++
                    menuFor = null
                },
                onChannel =
                    v.channelId?.let { id ->
                        {
                            menuFor = null
                            openChannel(id, v.channel ?: "Channel")
                        }
                    },
                onToggleSub =
                    v.channelId?.let { id ->
                        {
                            toggleSub(YouTubeSubscriptions.Sub(id, v.channel ?: "Channel"))
                            menuFor = null
                        }
                    },
            )
        }
        if (login != null) {
            val alreadySignedIn =
                remember(login, sessionTick) {
                    com.carteltv.tv.data.youtube.YouTubeSession
                        .signedIn()
                }
            var code by remember { mutableStateOf<YouTubeApiClient.DeviceCode?>(null) }
            var status by remember { mutableStateOf(if (alreadySignedIn) "Signed in" else "Requesting a sign-in code…") }
            val actionFocus =
                remember {
                    androidx.compose.ui.focus
                        .FocusRequester()
                }
            LaunchedEffect(alreadySignedIn) {
                if (alreadySignedIn) {
                    suspendRunCatching { actionFocus.awaitFocus() }
                    return@LaunchedEffect
                }
                if (login == "START") {
                    login = "POLL"
                    val started = YouTubeApiClient.beginLogin()
                    code = started
                    if (started == null) {
                        status = "Couldn't connect to YouTube."
                        return@LaunchedEffect
                    }
                    status = ""
                    // Poll for the code's whole life (Google keeps it ~30 min): bailing at
                    // 3 minutes killed codes while the viewer was still typing them.
                    val deadline = System.currentTimeMillis() + started.expiresInSec * 1000
                    while (System.currentTimeMillis() < deadline) {
                        when (val r = YouTubeApiClient.pollLogin(started.deviceCode)) {
                            "PENDING" -> {
                                kotlinx.coroutines.delay(started.intervalSec.coerceAtLeast(5) * 1000)
                            }

                            else -> {
                                if (r.startsWith("GRANTED:")) {
                                    com.carteltv.tv.data.youtube.YouTubeSession
                                        .granted(r.removePrefix("GRANTED:"))
                                    sessionTick++
                                    status = "Signed in"
                                    com.carteltv.tv.ui.components.TransientStatus
                                        .show("Signed in to YouTube.")
                                    refreshSubs(true)
                                    return@LaunchedEffect
                                }
                                if (r == "DENIED") {
                                    status = "Sign-in wasn't completed."
                                    return@LaunchedEffect
                                }
                                // Transient poll failures (network, slow_down races) must not
                                // kill a code the viewer may already have approved on the phone;
                                // only Google's terminal errors end the wait early.
                                val err = r.removePrefix("ERROR:")
                                if (err in
                                    setOf("invalid_client", "invalid_grant", "expired_token", "invalid_request")
                                ) {
                                    status = "Sign-in couldn't be verified."
                                    return@LaunchedEffect
                                }
                                kotlinx.coroutines.delay(started.intervalSec.coerceAtLeast(5) * 1000)
                            }
                        }
                    }
                    status = "The sign-in request expired."
                }
            }
            Box(
                Modifier.fillMaxSize().background(ThemeSemantics.scrimModal).dismissScrim { login = null },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .trapFocus()
                        .width(560.dp)
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .background(ThemeSemantics.surfaceRest)
                        .border(1.dp, YouTubeAccent.copy(alpha = 0.45f), RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .padding(ThemeSpec.Space.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The accent stays as the tag dot; the head itself reads in the house title voice.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(YouTubeAccent))
                        Spacer(Modifier.width(ThemeSpec.Space.sm))
                        Text(
                            if (alreadySignedIn) {
                                "YouTube account"
                            } else {
                                androidx.compose.ui.res.stringResource(
                                    com.carteltv.tv.R.string.youtube_sign_in,
                                )
                            },
                            fontSize = ThemeSpec.Type.title,
                            color = ThemeSemantics.textEmphatic,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(ThemeSpec.Space.md))
                    val c = code
                    when {
                        alreadySignedIn -> {
                            Text(
                                status,
                                fontSize = ThemeSpec.Type.body,
                                color = ThemeSemantics.textEmphatic,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(ThemeSpec.Space.md))
                            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                                ActionButton(
                                    "Sign out",
                                    filled = false,
                                    modifier = Modifier.focusRequester(actionFocus),
                                ) {
                                    com.carteltv.tv.data.youtube.YouTubeSession
                                        .signOut()
                                    accountShelves = emptyList()
                                    sessionTick++
                                    com.carteltv.tv.ui.components.TransientStatus
                                        .show("Signed out of YouTube.")
                                    refreshSubs(true)
                                    login = null
                                }
                                ActionButton("Close", filled = true) { login = null }
                            }
                        }

                        c == null -> {
                            Text(
                                status,
                                fontSize = ThemeSpec.Type.meta,
                                color = YouTubeDim,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }

                        else -> {
                            Text(
                                androidx.compose.ui.res
                                    .stringResource(com.carteltv.tv.R.string.youtube_open_other_device),
                                fontSize = ThemeSpec.Type.meta,
                                color = YouTubeDim,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                    Text(
                                        androidx.compose.ui.res
                                            .stringResource(com.carteltv.tv.R.string.youtube_scan_or_enter),
                                        fontSize = ThemeSpec.Type.meta,
                                        color = YouTubeDim,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        c.verifyUrl,
                                        fontSize = ThemeSpec.Type.body,
                                        color = ThemeSemantics.textEmphatic,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(vertical = ThemeSpec.Space.sm),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        androidx.compose.ui.res
                                            .stringResource(com.carteltv.tv.R.string.youtube_enter_auth_code),
                                        fontSize = ThemeSpec.Type.meta,
                                        color = YouTubeDim,
                                        modifier = Modifier.padding(bottom = ThemeSpec.Space.sm),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        c.userCode,
                                        fontSize = ThemeSpec.Type.display,
                                        color = ThemeSemantics.textEmphatic,
                                        fontWeight = FontWeight.Black,
                                        modifier =
                                            Modifier
                                                .background(
                                                    ThemeSemantics.textEmphatic.copy(alpha = 0.08f),
                                                    RoundedCornerShape(ThemeSpec.Card.corner),
                                                ).padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                                QrCode(text = c.verifyUrl, cellSize = 5.dp)
                            }
                            Text(
                                androidx.compose.ui.res
                                    .stringResource(com.carteltv.tv.R.string.youtube_device_hint),
                                fontSize = ThemeSpec.Type.label,
                                color = YouTubeDim,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = ThemeSpec.Space.sm),
                            )
                            Spacer(Modifier.height(ThemeSpec.Space.cozy))
                            if (status.isNotBlank()) {
                                Text(
                                    status,
                                    fontSize = ThemeSpec.Type.meta,
                                    color = YouTubeDim,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(ThemeSpec.Space.md))
                    Text(
                        androidx.compose.ui.res
                            .stringResource(com.carteltv.tv.R.string.youtube_back_close),
                        fontSize = ThemeSpec.Type.label,
                        color = YouTubeDim.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            BackHandler(enabled = true) { login = null }
        }
    }
}

@Composable
internal fun LoadingSpinner() {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = (tick + 1) % 3
            delay(320)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(if (i == tick) 14.dp else 10.dp)
                    .background(YouTubeAccent.copy(alpha = if (i == tick) 1f else 0.35f), androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}
