package com.carteltv.tv.ui.youtube

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Text
import com.carteltv.tv.data.youtube.YouTubeApiClient
import com.carteltv.tv.data.youtube.YouTubeLibrary
import com.carteltv.tv.data.youtube.YouTubeMusicClient
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.MusicArtCard
import com.carteltv.tv.ui.components.MusicShelfHeader
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.shimmer
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.theme.CartelBackdrop
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.glassPane
import kotlinx.coroutines.launch

private fun listenAgainShelf(): YouTubeMusicClient.Shelf? {
    val entries =
        YouTubeLibrary.history().take(12).map {
            YouTubeMusicClient.Entry(it.title, it.channel, it.thumb, it.videoId, playlistId = null)
        }
    return entries.takeIf { it.isNotEmpty() }?.let { YouTubeMusicClient.Shelf("Your history", "Listen again", it) }
}

/** The Music version of the YouTube section: shelves of playlists and tracks, played through the same player as the video screen. */
@Composable
fun YouTubeMusicScreen() {
    var shelves by remember { mutableStateOf<List<YouTubeMusicClient.Shelf>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var resolving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<YouTubeApiClient.YouTubeVideo?>(null) }
    var streams by remember { mutableStateOf<YouTubeApiClient.YouTubeStreams?>(null) }
    var queue by remember { mutableStateOf<List<YouTubeApiClient.YouTubeVideo>>(emptyList()) }
    var restoreVideoId by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    var restoreQueueIds by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // Fullscreen immersion while a video is playing, as on the video screen: keep the screen on
    // and hide the system bars so the player owns the whole display.
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(playing != null) {
        val activity = context as? android.app.Activity
        val window = activity?.window
        val active = playing != null
        if (active) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (active) {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun load() {
        busy = true
        notice = null
        scope.launch {
            val remote = kotlinx.coroutines.withTimeoutOrNull(15_000L) { YouTubeMusicClient.home() }.orEmpty()
            val listenAgain = listenAgainShelf()
            shelves = if (listenAgain != null) listOf(listenAgain) + remote else remote
            busy = false
            if (shelves.isEmpty()) notice = "Couldn't load YouTube Music. Check the connection and try again."
        }
    }
    LaunchedEffect(Unit) { load() }

    fun close() {
        playing = null
        streams = null
        restoreVideoId = null
        restoreQueueIds = emptyList()
    }

    fun play(
        track: YouTubeApiClient.YouTubeVideo,
        list: List<YouTubeApiClient.YouTubeVideo>,
    ) {
        resolving = true
        notice = null
        queue = list.ifEmpty { listOf(track) }
        restoreVideoId = track.videoId
        restoreQueueIds = queue.map { it.videoId }
        scope.launch {
            val s = YouTubeApiClient.resolve(track.videoId)
            resolving = false
            if (s == null || (s.videoUrl == null && s.muxedUrl == null && s.hlsUrl == null && s.audioUrl == null)) {
                notice = "That track isn't available right now."
                close()
            } else {
                streams = s.copy(title = track.title)
                playing = track
                YouTubeLibrary.noteWatched(track)
            }
        }
    }

    LaunchedEffect(Unit) {
        val id = restoreVideoId ?: return@LaunchedEffect
        val history = YouTubeLibrary.history()
        val restoredQueue = restoreQueueIds.mapNotNull { videoId -> history.firstOrNull { it.videoId == videoId } }
        val video =
            history.firstOrNull { it.videoId == id }
                ?: YouTubeApiClient.YouTubeVideo(id, "YouTube Music", channel = null, duration = null, thumb = null)
        play(video, restoredQueue.ifEmpty { listOf(video) })
    }

    fun playNextOrClose() {
        val at = queue.indexOfFirst { it.videoId == playing?.videoId }
        val next = if (at >= 0) queue.getOrNull(at + 1) else null
        if (next != null) play(next, queue) else close()
    }

    fun open(entry: YouTubeMusicClient.Entry) {
        resolving = true
        notice = null
        scope.launch {
            val list = entry.playlistId?.let { YouTubeMusicClient.queue(it, entry.videoId) }.orEmpty()
            val first = list.firstOrNull { it.videoId == entry.videoId } ?: list.firstOrNull()
            when {
                first != null -> {
                    play(first, list)
                }

                entry.videoId != null -> {
                    play(YouTubeApiClient.YouTubeVideo(entry.videoId, entry.title, entry.subtitle, null, entry.art), emptyList())
                }

                else -> {
                    resolving = false
                    notice = "Couldn't open that playlist."
                }
            }
        }
    }

    BackHandler(enabled = playing != null) { close() }

    val current = playing
    val live = streams
    // YouTube Music is a full-surface app: the global rail steps aside while it is open.
    com.carteltv.tv.ui.nav
        .HideBarWhile()
    Box(Modifier.fillMaxSize().background(YouTubeBg)) {
        if (current != null && live != null) {
            YouTubeEmbeddedPlayer(
                videoId = current.videoId,
                streams = live,
                next = queue.indexOfFirst { it.videoId == current.videoId }.let { at -> if (at >= 0) queue.getOrNull(at + 1) else null },
                channel = current.channel,
                onOpenQueue = {},
                onClosed = ::close,
                onEnded = ::playNextOrClose,
                onPlayNext = ::playNextOrClose,
            )
        } else {
            ScreenPage {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        when {
                            busy -> {
                                MusicSkeleton()
                            }

                            shelves.isEmpty() -> {
                                MusicEmptyState(notice, ::load)
                            }

                            else -> {
                                // Rail scroll offsets live here, not in each item, so a shelf scrolled away and back keeps its place.
                                val rowStates = remember { mutableStateMapOf<Int, LazyListState>() }
                                // Every rail parks its focused card on the same line, the way a streaming catalog
                                // keeps its sliders aligned while you browse down the page.
                                RailFocusLine {
                                    LazyColumn(
                                        Modifier.fillMaxSize(),
                                        contentPadding = PagePadding,
                                        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                                    ) {
                                        shelves.forEachIndexed { si, shelf ->
                                            item(key = "h:$si:${shelf.title}", contentType = "header") {
                                                MusicShelfHeader(
                                                    shelf.title,
                                                    Modifier.padding(
                                                        top =
                                                            if (si ==
                                                                0
                                                            ) {
                                                                0.dp
                                                            } else {
                                                                ThemeSpec.Space.xl
                                                            },
                                                    ),
                                                    shelf.eyebrow,
                                                )
                                            }
                                            // One shelf, one slider: the tiles run off the edge and feather away.
                                            item(key = "r:$si:${shelf.title}", contentType = "rail") {
                                                val rowState = rowStates.getOrPut(si) { LazyListState() }
                                                val wrapState = rememberRailWrapState()
                                                val wrapFirst = remember { FocusRequester() }
                                                LazyRow(
                                                    state = rowState,
                                                    // The peek of the next tile dissolves into the page instead of being sliced at the viewport.
                                                    modifier =
                                                        Modifier
                                                            .restoreRowFocus()
                                                            .railEdgeFade(rowState)
                                                            .railWrapToStart(wrapState, rowState, shelf.entries.size, wrapFirst),
                                                    // Vertical room so a lifted tile is not clipped by the row.
                                                    contentPadding = PaddingValues(vertical = ThemeSpec.Space.sm),
                                                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                                                ) {
                                                    itemsIndexed(shelf.entries, key = {
                                                        ei,
                                                        _,
                                                        ->
                                                        "c:$si:$ei"
                                                    }, contentType = { _, _ -> "card" }) { index, entry ->
                                                        MusicArtCard(
                                                            entry.title,
                                                            entry.subtitle,
                                                            entry.art,
                                                            width = 220.dp,
                                                            modifier =
                                                                Modifier
                                                                    .railWrapItem(wrapState, index)
                                                                    .then(if (index == 0) Modifier.focusRequester(wrapFirst) else Modifier),
                                                        ) { open(entry) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (notice != null && shelves.isNotEmpty()) {
                        Text(
                            notice.orEmpty(),
                            fontSize = ThemeSpec.Type.meta,
                            color = ThemeSemantics.textEmphatic,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = ThemeSpec.Space.xl)
                                    .background(
                                        ThemeSemantics.scrimChip,
                                        androidx.compose.foundation.shape
                                            .RoundedCornerShape(ThemeSpec.Card.cornerSm),
                                    ).padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
                        )
                    }
                }
            }
        }
        if (resolving) {
            Box(
                Modifier.fillMaxSize().background(ThemeSemantics.scrimBase.copy(alpha = 0.55f)),
                Alignment.Center,
            ) { LoadingSpinner() }
        }
    }
}

/** The empty / error panel: a glass card with the section glyph, a line of copy and one retry action. */
@Composable
private fun MusicEmptyState(
    message: String?,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
            modifier =
                Modifier
                    .width(520.dp)
                    .glassPane(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                    .padding(ThemeSpec.Space.xl),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                    .background(ThemeSemantics.accent),
                contentAlignment = Alignment.Center,
            ) {
                PhosphorIcon(com.carteltv.tv.R.drawable.ph_music_notes, ThemeSemantics.textInverse, 30.dp)
            }
            Text(
                "YouTube Music",
                fontSize = ThemeSpec.Type.titleL,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontFamily = ThemeSpec.Font.display,
                color = ThemeSemantics.textEmphatic,
                textAlign = TextAlign.Center,
            )
            Text(
                message.orEmpty(),
                fontSize = ThemeSpec.Type.body,
                fontFamily = ThemeSpec.Font.body,
                color = ThemeSemantics.textSecondary,
                textAlign = TextAlign.Center,
            )
            ActionButton("Retry", filled = true, modifier = Modifier.entryFocus()) { onRetry() }
        }
    }
}

/** The shelves while they load, in the shape they arrive in: a heading over a rail of square art tiles running off the edge. */
@Composable
private fun MusicSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(PagePadding).shimmer(),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xl),
    ) {
        repeat(2) { shelf ->
            Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                Box(
                    Modifier
                        .fillMaxWidth(
                            if (shelf ==
                                0
                            ) {
                                0.2f
                            } else {
                                0.28f
                            },
                        ).height(18.dp)
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                        .background(ThemeSemantics.surfaceRaised),
                )
                Box(
                    Modifier
                        .fillMaxWidth(
                            if (shelf ==
                                0
                            ) {
                                0.34f
                            } else {
                                0.26f
                            },
                        ).height(24.dp)
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                        .background(ThemeSemantics.surfaceRest),
                )
            }
            // A rail, not a grid row: fixed-width tiles that run past the edge the way the loaded shelves do.
            LazyRow(userScrollEnabled = false, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                items(6) {
                    Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                                .background(ThemeSemantics.surfaceRest),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(
                                    0.8f,
                                ).height(12.dp)
                                .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                                .background(ThemeSemantics.surfaceRaised),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(
                                    0.55f,
                                ).height(10.dp)
                                .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                                .background(ThemeSemantics.surfaceRaised),
                        )
                    }
                }
            }
        }
    }
}
