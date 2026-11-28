@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.GuideModel
import com.carteltv.tv.data.xtream.LiveChannels
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.engine.playback.LenientMediaSourceFactory
import com.carteltv.tv.engine.playback.LiveRetryDataSource
import com.carteltv.tv.engine.playback.PlaybackSession
import com.carteltv.tv.engine.stability.BackgroundWorkScheduler
import com.carteltv.tv.engine.stability.LiveLoadErrorPolicy
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.clickableTv
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.live.ChannelLogo
import com.carteltv.tv.ui.player.AudioFocusHandoff
import com.carteltv.tv.ui.player.BufferingPill
import com.carteltv.tv.ui.player.PlaybackStallGuard
import com.carteltv.tv.ui.player.ReportNowPlaying
import com.carteltv.tv.ui.player.buildCartelPlayer
import com.carteltv.tv.ui.player.cartelExtractors
import com.carteltv.tv.ui.player.cartelHttpFactory
import com.carteltv.tv.ui.player.cartelLiveMediaItem
import com.carteltv.tv.ui.player.neighbourChannel
import com.carteltv.tv.ui.player.providerDataSource
import com.carteltv.tv.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Up to four live tunings on one screen (XCIPTV-style multi-screen). Audio follows the
 * focused tile; the other tiles stay muted so one sound track plays at a time. Saved
 * arrangements live in [AppPreferences.multiViewLayouts] under the active provider.
 */
@Composable
fun MultiViewScreen(
    seedRefId: String,
    seedTitle: String,
    seedUrl: String,
    onClosed: () -> Unit,
) {
    val client = ProviderSession.current
    BackHandler { onClosed() }

    var slots by remember {
        mutableStateOf(
            mutableListOf<AppPreferences.MultiViewSlot?>(null, null, null, null).also {
                if (seedUrl.isNotBlank()) it[0] = AppPreferences.MultiViewSlot(seedRefId, seedTitle, seedUrl)
            },
        )
    }
    var cells by remember { mutableIntStateOf(if (seedUrl.isNotBlank()) 2 else 4) }
    var focusCell by remember { mutableIntStateOf(0) }
    var pickerFor by remember { mutableIntStateOf(-1) }
    var showLoadMenu by remember { mutableStateOf(false) }
    var savePrompt by remember { mutableStateOf(false) }

    val players = remember { mutableStateListOf<ExoPlayer?>(null, null, null, null) }
    val audioFocus = remember { AudioFocusHandoff() }

    /** Players that are actually tuning — derived so a player assignment only recomposes what reads it. */
    val tunedPlayers by remember { derivedStateOf { players.filterNotNull() } }

    fun tuned(): List<ExoPlayer> = tunedPlayers

    // Leaving the screen must not leave four decoders and four provider connections running unseen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        BackgroundWorkScheduler.enterPlayback()
        val paused = mutableListOf<ExoPlayer>()
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        tuned().filter { it.playWhenReady }.forEach {
                            runCatching { it.pause() }
                            paused += it
                        }
                    }

                    // Only tiles that were playing come back.
                    Lifecycle.Event.ON_START -> {
                        paused.forEach { runCatching { it.play() } }
                        paused.clear()
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            BackgroundWorkScheduler.exitPlayback()
        }
    }

    /** Play/pause is the screen's, not one tile's: the remote's play key pauses everything, or nothing. */
    fun togglePlayback(key: Key) {
        val play =
            when (key) {
                Key.MediaPlayPause -> tuned().none { it.playWhenReady }
                Key.MediaPlay -> true
                else -> false
            }
        tuned().forEach { if (play) it.play() else it.pause() }
    }

    /** Channel keys change the highlighted tile's channel, as they change the channel in the live player. */
    fun zap(direction: Int) {
        val slot = slots.getOrNull(focusCell) ?: return
        neighbourChannel(slot.refId, direction)?.let { next ->
            slots = slots.toMutableList().also { it[focusCell] = next }
        }
    }

    Column(
        Modifier.fillMaxSize().background(ThemeSemantics.scrimBase).onPreviewKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown || pickerFor >= 0 || showLoadMenu || savePrompt) return@onPreviewKeyEvent false
            when (ev.key) {
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                    togglePlayback(ev.key)
                    true
                }

                Key.ChannelUp, Key.MediaNext -> {
                    zap(1)
                    true
                }

                Key.ChannelDown, Key.MediaPrevious -> {
                    zap(-1)
                    true
                }

                // A live tile has nothing to seek in, and these must not reach whatever sits behind the screen.
                Key.MediaFastForward, Key.MediaRewind -> {
                    true
                }

                else -> {
                    false
                }
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ThemeSpec.Safe.horizontal, vertical = ThemeSpec.Space.sm),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(2 to "2 tiles", 3 to "3 tiles", 4 to "4 tiles").forEach { (n, label) ->
                FilterChip(label, cells == n) { cells = n }
            }
            FilterChip("Save", false) { savePrompt = true }
            FilterChip("Load", false) { showLoadMenu = true }
            Spacer(Modifier.weight(1f))
            Text(
                "Audio plays from the highlighted tile",
                fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val rows =
            if (cells == 3) {
                1
            } else if (cells == 4) {
                2
            } else {
                1
            }
        val cols = if (cells == 3) 3 else 2
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
            repeat(rows) { r ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
                    repeat(cols) { c ->
                        val i = r * cols + c
                        if (i >= cells) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            // Two different states: the tile the sound comes from, and the tile the D-pad is on.
                            val carrier = focusCell == i
                            val slot = slots[i]
                            val tile = remember(slot?.url) { TileState() }
                            var tileFocused by remember { mutableStateOf(false) }
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            Box(
                                Modifier
                                    .weight(1f)
                                    .then(if (i == 0) Modifier.entryFocus() else Modifier)
                                    .fillMaxHeight()
                                    // Tiles run flush to the screen edges, so a lifted tile would push its own ring off
                                    // screen and under its neighbour: focus is the ring alone, and only an OK press dips.
                                    .focusLift(focusScaleState(tileFocused, ThemeSpec.Focus.liftHero, pressed))
                                    // Accent on the resting edge marks the audio carrier and nothing else; focus is the white ring.
                                    .glassSurface(
                                        tileFocused,
                                        RoundedCornerShape(ThemeSpec.Card.corner),
                                        idle = if (carrier) ThemeSemantics.accent else Color.Transparent,
                                    ).focusable()
                                    .onFocusChanged {
                                        tileFocused = it.isFocused
                                        if (it.isFocused) focusCell = i
                                    }
                                    // A failed tile is its own Retry button; any other tile opens the channel picker.
                                    .clickableNoIndication(interaction) { if (tile.failed) tile.retry() else pickerFor = i },
                            ) {
                                if (slot != null) {
                                    TilePlayer(slot, carrier, tile, audioFocus, Modifier.fillMaxSize()) { players[i] = it }
                                    Text(
                                        GuideModel.displayName(slot.title),
                                        fontSize = ThemeSpec.Type.label,
                                        fontWeight = FontWeight.Bold,
                                        color = ThemeSemantics.textEmphatic,
                                        // Over live video, so the name carries its own chip.
                                        modifier =
                                            Modifier
                                                .align(Alignment.TopStart)
                                                .padding(ThemeSpec.Safe.horizontal, ThemeSpec.Safe.vertical)
                                                .background(ThemeSemantics.scrimChip, RoundedCornerShape(ThemeSpec.Card.pill))
                                                .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (tile.failed) {
                                        TileFailure(tileFocused, Modifier.fillMaxSize())
                                    } else if (tile.loading) {
                                        BufferingPill(Modifier.align(Alignment.Center))
                                    }
                                } else {
                                    Text(
                                        "Press OK to add a channel",
                                        fontSize = ThemeSpec.Type.body,
                                        color = ThemeSemantics.textSecondary,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Playback state follows the tile the audio comes from. derivedStateOf keeps ReportNowPlaying
    // from recomposing the whole screen when an unrelated tile's player changes.
    val context = LocalContext.current
    val shownSlot by remember { derivedStateOf { slots.getOrNull(focusCell) } }
    val shownPlayer by remember { derivedStateOf { players.getOrNull(focusCell) } }
    val slot = shownSlot
    val player = shownPlayer
    if (slot != null && player != null) {
        val channelTitle = GuideModel.displayName(slot.title)
        ReportNowPlaying(player, channelTitle, "Live")
        // The audio tile also answers the system's media keys: one session, rebuilt as focus
        // moves to another tile. A remote stop pauses the whole screen, exactly as its own
        // play key does; the live item already advertises no seeking.
        DisposableEffect(player, channelTitle) {
            val session =
                PlaybackSession(
                    context,
                    player,
                    isLive = true,
                    onStop = { tuned().forEach { p -> runCatching { p.pause() } } },
                )
            onDispose { session.release() }
        }
    }

    if (pickerFor >= 0 && client != null) {
        ChannelPicker(
            onPick = { s ->
                slots = slots.toMutableList().also { it[pickerFor] = s }
                if (pickerFor >= cells && cells < 4) cells = pickerFor + 1
                pickerFor = -1
            },
            onDismiss = { pickerFor = -1 },
        )
    }

    if (savePrompt) {
        SaveNameDialog(
            onName = { name ->
                AppPreferences.saveMultiViewLayout(AppPreferences.activeProviderId, AppPreferences.MultiViewLayout(name, slots.take(cells)))
                savePrompt = false
            },
            onDismiss = { savePrompt = false },
        )
    }

    if (showLoadMenu) {
        // Asked for once. From composition proper, every recomposition behind the dialog would file a new one.
        LaunchedEffect(showLoadMenu) {
            val saved = AppPreferences.multiViewLayouts(AppPreferences.activeProviderId)
            TvDialogs.menu(
                "Saved layouts",
                saved.map { it.name } + if (saved.isNotEmpty()) listOf("Delete…") else emptyList(),
                cancelLabel = "Back",
                onCancel = { showLoadMenu = false },
            ) { i ->
                if (saved.isEmpty() || i == saved.size) {
                    if (saved.isNotEmpty()) {
                        TvDialogs.menu(
                            "Select a layout to delete",
                            saved.map { it.name },
                            cancelLabel = "Back",
                            onCancel = {},
                        ) { j -> AppPreferences.deleteMultiViewLayout(AppPreferences.activeProviderId, saved[j].name) }
                    }
                } else {
                    val l = saved[i]
                    slots = List(4) { k -> l.slots.getOrNull(k) }.toMutableList()
                    cells = l.slots.count { it != null }.coerceIn(2, 4)
                    focusCell = 0
                }
                showLoadMenu = false
            }
        }
    }
}

/** What one tile is showing, driven by its own player's listener. The cell reads it to draw over the picture. */
private class TileState {
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    lateinit var retry: () -> Unit
}

/** One live tuning in a cell: the shared player recipe, no deck, muted unless highlighted, no captions. */
@Composable
private fun TilePlayer(
    slot: AppPreferences.MultiViewSlot,
    focused: Boolean,
    tile: TileState,
    audioFocus: AudioFocusHandoff,
    modifier: Modifier = Modifier,
    onPlayer: (ExoPlayer?) -> Unit,
) {
    val context = LocalContext.current
    val url = slot.url
    val meta = remember(url) { ProviderSession.current?.streamMeta(url) }
    val player =
        remember(url) {
            // Tile parity with the main player: live progressive retry resets the byte position,
            // segment failures surface fast instead of grinding through Media3's stock retries,
            // and the item carries the live-edge cushion.
            buildCartelPlayer(
                context,
                live = true,
                deepBuffer = AppPreferences.deepBufferActive,
                sourceFactory =
                    LenientMediaSourceFactory(
                        providerDataSource(context, LiveRetryDataSource.Factory(cartelHttpFactory(meta?.headers.orEmpty()))),
                        cartelExtractors(context),
                        loadErrorPolicy = LiveLoadErrorPolicy(),
                    ),
                audioFocus = false,
            ).build()
        }
    val watchdog = remember(url) { PlaybackStallGuard(maxRetries = 1) }

    // Audio follows the highlighted tile, and so does audio focus: one tile holds it for the whole screen.
    val holder = remember(player) { AudioFocusHandoff.Holder { hold -> player.setAudioAttributes(AudioAttributes.DEFAULT, hold) } }
    LaunchedEffect(player, focused) {
        player.volume = if (focused) 1f else 0f
        if (focused) audioFocus.claim(holder) else audioFocus.release(holder)
    }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            tile.loading = false
                            tile.failed = false
                        }

                        Player.STATE_BUFFERING -> {
                            if (!tile.failed) tile.loading = true
                        }

                        else -> {
                            tile.loading = false
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // The message can carry the stream URL, and provider URLs carry the account's sign-in.
                    android.util.Log.w("CartelTile", "Tile error [${error.errorCodeName}]")
                    tile.loading = false
                    tile.failed = true
                }
            }

        fun retry() {
            tile.failed = false
            tile.loading = true
            watchdog.reset()
            player.stop()
            player.prepare()
            player.playWhenReady = true
        }
        player.addListener(listener)
        tile.retry = ::retry
        player.setMediaItem(cartelLiveMediaItem(url, meta))
        watchdog.attemptStarted(android.os.SystemClock.elapsedRealtime())
        player.prepare()
        player.playWhenReady = true
        onPlayer(player)
        onDispose {
            // The cell forgets this player before the release, so a play/pause key never meets a released one.
            onPlayer(null)
            player.removeListener(listener)
            player.release()
        }
    }

    // A tile that never starts, or stops feeding, ends on the failure card, not on a spinner that never ends.
    LaunchedEffect(player, watchdog) {
        while (true) {
            delay(1_000L)
            when (watchdog.check(android.os.SystemClock.elapsedRealtime(), player.playbackState, player.playWhenReady)) {
                PlaybackStallGuard.Outcome.None -> {
                    Unit
                }

                // One second between the stop and the re-prepare so four tiles hit by the same
                // provider outage do not reconnect as one herd.
                PlaybackStallGuard.Outcome.Retry -> {
                    delay(1_000L)
                    player.stop()
                    player.prepare()
                }

                is PlaybackStallGuard.Outcome.GiveUp -> {
                    player.stop()
                    tile.loading = false
                    tile.failed = true
                }
            }
        }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                isFocusable = false
            }
        },
        modifier = modifier,
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}

/**
 * Over a channel that would not play. A dead channel must not read as a quiet black tile, so the tile
 * says so and becomes its own Retry button: the chip lights up with the tile's focus, and OK retries.
 */
@Composable
private fun TileFailure(
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.background(ThemeSemantics.scrimModal).padding(horizontal = ThemeSpec.Safe.horizontal, vertical = ThemeSpec.Safe.vertical),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "This channel can't be played. Press OK to retry.",
                fontSize = ThemeSpec.Type.body,
                color = ThemeSemantics.textEmphatic,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            Spacer(Modifier.height(ThemeSpec.Space.md))
            // The tile is the button, so the chip borrows the tile's focus: the deck's surface, ring and lift.
            Text(
                "Retry",
                fontSize = ThemeSpec.Type.meta,
                fontWeight = FontWeight.Bold,
                color = ThemeSemantics.textEmphatic,
                modifier =
                    Modifier
                        .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact))
                        .glassSurface(focused, RoundedCornerShape(ThemeSpec.Card.cornerSm))
                        .padding(horizontal = ThemeSpec.Space.xl, vertical = ThemeSpec.Space.sm),
            )
        }
    }
}

/** Full channel list with letter-jump: press a key to snap to the first channel starting with it. */
@Composable
private fun ChannelPicker(
    onPick: (AppPreferences.MultiViewSlot) -> Unit,
    onDismiss: () -> Unit,
) {
    val client =
        ProviderSession.current ?: run {
            onDismiss()
            return
        }
    // Composed after the screen's own Back handler, so Back closes the picker instead of the whole screen.
    BackHandler { onDismiss() }
    var channels by remember { mutableStateOf(listOf<com.carteltv.tv.data.xtream.XtreamClient.LiveStream>()) }
    LaunchedEffect(Unit) {
        channels = withContext(Dispatchers.IO) { LiveChannels.all(client) }
    }
    val listState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(channels) { if (channels.isNotEmpty()) firstFocus.awaitFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(ThemeSemantics.scrimModal)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val ch = ev.key.uiChar ?: return@onKeyEvent false
                val idx = channels.indexOfFirst { GuideModel.displayName(it.name).startsWith(ch, true) }
                if (idx >= 0) {
                    scope.launch { listState.scrollToItem(idx) }
                    true
                } else {
                    false
                }
            }.clickableTv(hoverEffect = false, showHand = false) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.7f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .background(ThemeSemantics.surfaceRest)
                .clickableTv(hoverEffect = false, showHand = false) { }
                .padding(ThemeSpec.Space.cozy),
        ) {
            Text(
                androidx.compose.ui.res
                    .stringResource(com.carteltv.tv.R.string.multiview_pick_channel),
                fontSize = ThemeSpec.Type.title,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.broadcast,
                color = ThemeSemantics.textEmphatic,
            )
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
            ) {
                itemsIndexed(channels, key = { _, s -> s.streamId }) { i, s ->
                    val interaction = remember { MutableInteractionSource() }
                    val hot by interaction.collectIsFocusedAsState()
                    val pressed by interaction.collectIsPressedAsState()
                    // A wall of near-identical text rows cannot be scanned at TV distance; the logo names the
                    // channel the way the guide and Home's shelves do, and the line beneath confirms it.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusLift(focusScaleState(hot, ThemeSpec.Focus.liftCompact, pressed))
                                .glassSurface(hot, RoundedCornerShape(ThemeSpec.Card.corner))
                                .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                .clickableNoIndication(interaction) {
                                    onPick(AppPreferences.MultiViewSlot("live:${s.streamId}", s.name, client.liveUrl(s.streamId)))
                                }.padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.sm),
                    ) {
                        ChannelLogo(s, ThemeSpec.Space.xl * 3)
                        Spacer(Modifier.height(ThemeSpec.Space.sm))
                        Text(
                            GuideModel.displayName(s.name),
                            fontSize = ThemeSpec.Type.label,
                            color = if (hot) ThemeSemantics.textEmphatic else ThemeSemantics.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveNameDialog(
    onName: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Asked for once, so the dialog outlives the recompositions of the screen behind it.
    LaunchedEffect(Unit) {
        TvDialogs.input(
            "Save layout",
            message = "Enter a name for this layout.",
            onCancel = onDismiss,
        ) { name -> if (name.isBlank()) onDismiss() else onName(name.trim()) }
    }
}

/** The printable character a remote digit/letter key maps to, for the picker jump. */
private val Key.uiChar: String?
    get() =
        when (this) {
            Key.Zero -> "0"
            Key.One -> "1"
            Key.Two -> "2"
            Key.Three -> "3"
            Key.Four -> "4"
            Key.Five -> "5"
            Key.Six -> "6"
            Key.Seven -> "7"
            Key.Eight -> "8"
            Key.Nine -> "9"
            Key.A -> "A"
            Key.B -> "B"
            Key.C -> "C"
            Key.D -> "D"
            Key.E -> "E"
            Key.F -> "F"
            Key.G -> "G"
            Key.H -> "H"
            Key.I -> "I"
            Key.J -> "J"
            Key.K -> "K"
            Key.L -> "L"
            Key.M -> "M"
            Key.N -> "N"
            Key.O -> "O"
            Key.P -> "P"
            Key.Q -> "Q"
            Key.R -> "R"
            Key.S -> "S"
            Key.T -> "T"
            Key.U -> "U"
            Key.V -> "V"
            Key.W -> "W"
            Key.X -> "X"
            Key.Y -> "Y"
            Key.Z -> "Z"
            else -> null
        }
