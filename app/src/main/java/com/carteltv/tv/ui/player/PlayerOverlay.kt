@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.player
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.trapFocus
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.live.hhmm
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.components.ClockFormat
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.clickableTv
import com.carteltv.tv.ui.components.rememberClock
import kotlinx.coroutines.delay

/** The deck leaves itself on screen for this long after the last key. */
private const val AUTO_HIDE_MS = 5_000L
private const val ADVANCED_HIDE_MS = 12_000L

/** The deck rises by this fraction of the screen height as it fades in. */
private const val DECK_RISE_FRACTION = 12

/** Metadata the overlay needs about what's playing. */
data class PlayerMeta(
    val title: String = "",
    val subtitle: String = "",       // channel name / episode info
    val isLive: Boolean = false,
    val posterUrl: String = "",
    val refId: String = "",
    /** Optional: next programme title for live EPG. */
    val nextTitle: String = "",
    /** VOD series only: label of the next episode, empty when unknown or last. */
    val nextEpisodeLabel: String = "",
    /** VOD series only: playback refId of the next episode, used to look up its art online. */
    val nextRefId: String = "",
    /** Live only: the stream being watched, so a reminder can tune back in to it. */
    val streamUrl: String = "",
    /** Optional: stream info for data pool. */
    val streamInfo: Map<String, String> = emptyMap(),
    /** Live only: the channel as the viewer knows it, and what is on it now and next. */
    val channelName: String = "",
    val logoUrl: String? = null,
    /** Live only: the number the channel goes by on the remote, 0 when unknown. */
    val channelNumber: Int = 0,
    val nowTitle: String = "",
    val nowStartMs: Long = 0L,
    val nowStopMs: Long = 0L,
    val nextStartMs: Long = 0L,
    /** Upcoming queue/programme titles (up to 3 shown in info panel). */
    val queueTitles: List<String> = emptyList(),
)

@Composable
internal fun PlayerTopStatus(meta: PlayerMeta, info: Map<String, String>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        if (meta.isLive) {
            Row(
                Modifier.clip(CircleShape)
                    .background(ThemeSemantics.live.copy(alpha = 0.20f))
                    .border(1.dp, ThemeSemantics.live.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = ThemeSpec.Space.md, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) {
                LiveDot()
                Text("LIVE", fontSize = ThemeSpec.Type.micro, fontWeight = FontWeight.Bold, letterSpacing = ThemeSpec.Type.trackCaps, color = PlayerChrome.ink, maxLines = 1)
            }
            if (!meta.logoUrl.isNullOrBlank()) {
                coil3.compose.AsyncImage(
                    model = meta.logoUrl,
                    contentDescription = meta.channelName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.size(width = 74.dp, height = 40.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                        .background(PlayerChrome.well).padding(ThemeSpec.Space.xs),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        listOfNotNull(
            info["Video class"],
            info["HDR class"]?.takeUnless { it.equals("SDR", ignoreCase = true) },
            info["Audio layout"],
        ).take(3).forEach { PlayerStatusBadge(it, PlayerChrome.inkMuted) }
    }
}

@Composable
private fun PlayerStatusBadge(label: String, color: Color) {
    Text(
        label,
        fontSize = ThemeSpec.Type.micro,
        fontWeight = FontWeight.Bold,
        letterSpacing = ThemeSpec.Type.trackCaps,
        color = color,
        fontFamily = ThemeSpec.Font.mono,
        maxLines = 1,
        modifier = Modifier
            .playerWell(false, CircleShape)
            .padding(horizontal = ThemeSpec.Space.md, vertical = 3.dp),
    )
}

@Composable
fun PlayerOverlay(
    player: ExoPlayer,
    meta: PlayerMeta,
    remote: PlayerRemote,
    modifier: Modifier = Modifier,
    /** With a next item: swap to it from the deck's EP button. Null when there is none. */
    onPlayNext: (() -> Unit)? = null,
    /** Aspect and subtitle size, owned by the host because both change what it renders. */
    geometry: VideoGeometry,
    /** Where a new speed is kept. Per title by default, as the main player always has. */
    onSpeedChange: (Float) -> Unit = { speed -> if (meta.refId.isNotBlank()) AppPreferences.setTitleSpeed(meta.refId, speed) },
    /** The new Voice boost level is already saved. Renderers are fixed when a player is built, so the host rebuilds it. */
    onVoiceBoostChange: () -> Unit = {},
    onOpenSidePanel: (() -> Unit)? = null,
    onOpenSchedule: (() -> Unit)? = null,
    onSourceSwitch: (() -> Unit)? = null,
    onMultiview: (() -> Unit)? = null,
    onOpenPictureInPicture: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    /** Optional extra deck action (e.g. comments). */
    onToggleComments: (() -> Unit)? = null,
    commentsActive: Boolean = false,
    /** Optional external right-side panel owned by the host (e.g. comments). */
    extraSidePanelOpen: Boolean = false,
    onDismissExtraSidePanel: () -> Unit = {},
    extraSidePanel: (@Composable BoxScope.(dismiss: () -> Unit) -> Unit)? = null,
    /** Non-modal overlay content drawn above video and below modal panels. */
    overlayContent: (@Composable BoxScope.() -> Unit)? = null,
    /** File chapter starts, drawn as ticks on the deck's scrubber. */
    chapters: List<Long> = emptyList(),
) {
    val deck = remote.deck
    val state = deck.value

    var hideTick by remember { mutableIntStateOf(0) }
    var showTrackPicker by remember { mutableStateOf<TrackType?>(null) }
    var showDataPool by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(player.volume.coerceIn(0f, 1f)) }
    var lastVolume by remember { mutableFloatStateOf(if (player.volume > 0f) player.volume else 1f) }
    val muted = volume <= 0f
    val modalOpen = showTrackPicker != null || showDataPool || showInfo || extraSidePanelOpen
    LaunchedEffect(state, hideTick, modalOpen) {
        if (state != PlayerControlState.HIDDEN && !modalOpen) {
            delay(if (state == PlayerControlState.TILES) ADVANCED_HIDE_MS else AUTO_HIDE_MS)
            if (deck.value != PlayerControlState.HIDDEN) deck.value = PlayerControlState.HIDDEN
        }
    }

    fun resetHide() { hideTick++ }

    fun setVolume(next: Float) {
        val v = next.coerceIn(0f, 1f)
        volume = v
        if (v > 0f) lastVolume = v
        player.volume = v
        resetHide()
    }

    fun showBar() {
        deck.value = PlayerControlState.BAR
        resetHide()
    }

    fun showOptions() {
        deck.value = PlayerControlState.TILES
        resetHide()
    }

    fun hideAll() {
        showInfo = false
        deck.value = PlayerControlState.HIDDEN
    }

    fun toggleMute() {
        setVolume(if (muted) lastVolume.coerceAtLeast(0.1f) else 0f)
        if (deck.value == PlayerControlState.HIDDEN) showOptions()
    }

    fun onOk() {
        when (deck.value) {
            PlayerControlState.HIDDEN -> showOptions()
            PlayerControlState.BAR -> showOptions()
            else -> showBar()
        }
    }

    fun onBack() {
        when {
            showInfo -> { showInfo = false; resetHide() }
            extraSidePanelOpen -> { onDismissExtraSidePanel(); resetHide() }
            deck.value == PlayerControlState.TILES -> showBar()
            deck.value == PlayerControlState.BAR -> hideAll()
            else -> remote.onClose()
        }
    }

    fun onUp() {
        if (deck.value == PlayerControlState.HIDDEN) showBar()
    }

    fun onDown() {
        if (deck.value == PlayerControlState.HIDDEN) showBar()
    }

    /** MENU: a dialog of the deck closes, a shown deck hides, a hidden deck shows. */
    fun onMenu() {
        when {
            showTrackPicker != null -> showTrackPicker = null
            showDataPool -> showDataPool = false
            showInfo -> showInfo = false
            extraSidePanelOpen -> onDismissExtraSidePanel()
            deck.value != PlayerControlState.HIDDEN -> hideAll()
            else -> showOptions()
        }
    }

    var deckFocusTick by remember { mutableIntStateOf(0) }
    // Every closure here reads live state through remembered holders, so one object serves the overlay's life.
    val control = remember(remote) {
        object : PlayerControl {
            override val modal: Boolean get() = showTrackPicker != null || showDataPool || showInfo || extraSidePanelOpen
            override fun show() = showBar()
            override fun back() = onBack()
            override fun up() = onUp()
            override fun down() = onDown()
            override fun toggle() = onMenu()
            override fun touch() { hideTick++ }
            override fun refocus() { deckFocusTick++ }
        }
    }
    SideEffect {
        remote.control = control
        remote.host.onVolume = { dir ->
            setVolume(player.volume + dir * 0.05f)
            if (deck.value == PlayerControlState.HIDDEN) showOptions()
            true
        }
        remote.host.onMute = {
            val current = player.volume
            if (current > 0f) {
                lastVolume = current
                setVolume(0f)
            } else {
                setVolume(lastVolume.coerceAtLeast(0.1f))
            }
            if (deck.value == PlayerControlState.HIDDEN) showOptions()
            true
        }
        remote.host.onSettings = {
            showInfo = false
            if (deck.value == PlayerControlState.HIDDEN) showOptions()
            deck.value = PlayerControlState.TILES
            resetHide()
            true
        }
        remote.host.onInfo = {
            if (deck.value == PlayerControlState.HIDDEN) showOptions()
            showInfo = !showInfo
            resetHide()
            true
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (remote.control === control) remote.control = null }
    }

    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { buffering = playbackState == Player.STATE_BUFFERING }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    /** Aspect is owned by the host (it changes what the surface renders), so this overlay only labels it. */
    fun aspectName(m: Int) = when (m) {
        0 -> "Fit"
        1 -> "Cinema"
        2 -> "Zoom"
        else -> "Stretch"
    }

    var speedTick by remember { mutableIntStateOf(0) }
    val speeds = remember { floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f) }
    val currentSpeed = remember(speedTick) { player.playbackParameters.speed }

    fun cycleSpeed() {
        val idx = speeds.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.01f }
        val next = speeds[(idx + 1) % speeds.size]
        player.setPlaybackSpeed(next)
        onSpeedChange(next)
        speedTick++
    }

    var boostTick by remember { mutableIntStateOf(0) }
    val boostLevel = remember(boostTick) { AppPreferences.dialogueBoost }

    fun cycleBoost() {
        AppPreferences.dialogueBoost = (AppPreferences.dialogueBoost + 1) % DialogueBoost.LEVELS
        boostTick++
        onVoiceBoostChange()
    }

    var dataPoolTick by remember { mutableIntStateOf(0) }
    val dataPoolInfo = remember(dataPoolTick) { buildDataPoolInfo(player, meta) }

    val now = rememberClock()

    Box(modifier.fillMaxSize()) {
        /* buffering shares the stage with transient seek/live HUDs: one status pill at a time */
        val transientHud = remote.seekTarget != null || remote.liveOffset != null
        if (buffering && !transientHud) BufferingPill(Modifier.align(Alignment.Center))

        /* the seek HUD sits under the deck, so a deck opened mid-seek covers it */
        remote.seekTarget?.let { target ->
            SeekHud(target, runCatching { player.duration }.getOrDefault(0L), remote.host.seekThumb(), Modifier.align(Alignment.BottomStart))
        }
        if (remote.live()) remote.liveOffset?.let { behind ->
            LiveOffsetPill(behind, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }

        /* deck: compact in B, settings tiles added in C. It fades in with a small rise and sinks away. */
        AnimatedVisibility(
            visible = state == PlayerControlState.BAR || state == PlayerControlState.TILES,
            enter = fadeIn(ThemeSpec.Motion.tweenSlow()) + slideInVertically(ThemeSpec.Motion.tweenSlow()) { it / DECK_RISE_FRACTION },
            exit = fadeOut(ThemeSpec.Motion.tweenMid()) + slideOutVertically(ThemeSpec.Motion.tweenMid()) { it / DECK_RISE_FRACTION },
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter)) {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(PlayerChrome.scrimTop, Color.Transparent),
                        ),
                    )
                }

                Canvas(Modifier.fillMaxWidth().height(340.dp).align(Alignment.BottomCenter)) {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, PlayerChrome.scrimBottom),
                        ),
                    )
                }

                PlayerTopStatus(
                    meta = meta,
                    info = dataPoolInfo,
                    modifier = Modifier.align(Alignment.TopStart).padding(
                        top = ThemeSpec.Safe.vertical,
                        start = ThemeSpec.Safe.horizontal,
                        end = ThemeSpec.Safe.horizontal + 96.dp,
                    ),
                )

                if (AppPreferences.playerClock) {
                    Text(
                        ClockFormat.time(now.time, AppPreferences.clock24),
                        fontSize = ThemeSpec.Type.meta, color = PlayerChrome.ink,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = ThemeSpec.Safe.vertical, end = ThemeSpec.Safe.horizontal)
                            .playerGlass(RoundedCornerShape(ThemeSpec.Card.pill), elevation = 12.dp)
                            .padding(horizontal = ThemeSpec.Space.md, vertical = 3.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                PlayerControlBar(
                    player = player,
                    meta = meta,
                    onPlayPause = { if (player.isPlaying) player.pause() else player.play(); resetHide() },
                    onSettings = {
                        showInfo = false
                        showTrackPicker = null
                        showDataPool = false
                        deck.value = if (deck.value == PlayerControlState.TILES) PlayerControlState.BAR else PlayerControlState.TILES
                        resetHide()
                    },
                    onInfo = { showInfo = !showInfo; resetHide() },
                    onClose = { remote.onClose() },
                    onShowTracks = { showTrackPicker = it; resetHide() },
                    onShowDataPool = { showDataPool = true; dataPoolTick++; resetHide() },
                    onCycleAspect = { geometry.cycleAspect(); resetHide() },
                    onCycleSpeed = { cycleSpeed(); resetHide() },
                    onCycleBoost = { cycleBoost(); resetHide() },
                    onCycleSubScale = { geometry.cycleSubtitleScale(); resetHide() },
                    onPlayNext = onPlayNext,
                    onOpenSidePanel = onOpenSidePanel,
                    onOpenSchedule = onOpenSchedule,
                    onSourceSwitch = onSourceSwitch,
                    onMultiview = onMultiview,
                    onOpenPictureInPicture = onOpenPictureInPicture,
                    onSearch = onSearch,
                    onToggleComments = onToggleComments,
                    commentsActive = commentsActive,
                    onLastChannel = remote.host.onLast?.let { last -> { last() } },
                    onResetHide = { resetHide() },
                    refocus = deckFocusTick,
                    expanded = state == PlayerControlState.TILES,
                    aspectName = aspectName(geometry.aspectMode),
                    currentSpeed = currentSpeed,
                    boostLevel = boostLevel,
                    subScale = geometry.subtitleScale,
                    chapters = chapters,
                )
            }
        }

        if (overlayContent != null && state != PlayerControlState.HIDDEN) {
            overlayContent()
        }

        if (showInfo && state != PlayerControlState.HIDDEN) {
            PlayerInfoPanel(
                meta = meta,
                onDismiss = { showInfo = false; control.refocus() },
            )
        }

        if (extraSidePanelOpen && state != PlayerControlState.HIDDEN && extraSidePanel != null) {
            BackHandler { onDismissExtraSidePanel(); control.refocus() }
            extraSidePanel { onDismissExtraSidePanel(); control.refocus() }
        }

        if (showDataPool && state != PlayerControlState.HIDDEN) {
            BackHandler { showDataPool = false; control.refocus() }
            Box(
                Modifier.fillMaxSize().background(ThemeSemantics.scrimModal).dismissScrim { showDataPool = false; control.refocus() },
                contentAlignment = Alignment.Center,
            ) {
                val poolFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { suspendRunCatching { poolFocus.awaitFocus() } }
                Box(
                    Modifier.trapFocus().focusRequester(poolFocus)
                        .clickableTv(hoverEffect = false, showHand = false) { showDataPool = false; control.refocus() },
                ) { DataPoolCard(info = dataPoolInfo) }
            }
        }

        showTrackPicker?.let { type ->
            TrackPickerOverlay(
                player = player,
                trackType = type,
                onDismiss = { showTrackPicker = null; control.refocus() },
            )
        }
    }
}

@Composable
private fun DataPoolCard(info: Map<String, String>) {
    Column(
        Modifier.widthIn(max = 520.dp)
            .playerGlass(RoundedCornerShape(ThemeSpec.Card.cornerLg + 4.dp), heavy = true, elevation = 18.dp)
            // Dense streams push dozens of rows past the screen; cap the plate and scroll inside it.
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(ThemeSpec.Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            PlayerGlyphIcon(PlayerGlyph.INFO, ThemeSemantics.accentBright, 20.dp)
            Text(
                "Stream information",
                fontSize = ThemeSpec.Type.title, fontWeight = FontWeight.Bold, fontFamily = ThemeSpec.Font.broadcast, color = PlayerChrome.ink,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Text("Back closes", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, maxLines = 1)
        Spacer(Modifier.height(ThemeSpec.Space.md))
        info.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = ThemeSpec.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(key, fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkDim, modifier = Modifier.width(130.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(value, fontSize = ThemeSpec.Type.label, color = PlayerChrome.ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (info.isEmpty()) {
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.player_no_stats), fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkDim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Resolution tier: SD < HD 720 < FHD 1080 < UHD 4K < 8K. Empty when the format has no geometry yet. */
internal fun resolutionClass(width: Int, height: Int): String = when {
    height >= 4320 || width >= 7680 -> "8K UHD"
    height >= 2160 || width >= 3840 -> "UHD 4K"
    height >= 1080 || width >= 1920 -> "FHD 1080p"
    height >= 720 || width >= 1280 -> "HD 720p"
    height > 0 -> "SD"
    else -> ""
}

/** Speaker layout: mono / stereo / 5.1 / 7.1 by channel count. */
internal fun channelLayout(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    in 3..5 -> "Multichannel"
    else -> if (channels > 0) "${channels}ch" else ""
}

/** Dynamic range family: Dolby Vision (dvhe/dvh1/dvav/dva1/dav1 codec tag) over HDR10 (PQ) over HLG, else SDR. */
internal fun hdrClass(colorInfo: androidx.media3.common.ColorInfo?, codecs: String? = null): String {
    val tag = codecs?.lowercase().orEmpty()
    val c = colorInfo
    return when {
        "dvh1" in tag || "dvhe" in tag || "dvav" in tag || "dva1" in tag || "dav1" in tag -> "Dolby Vision"
        c?.hdrStaticInfo != null && c.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
        c?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
        c?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR"
    }
}

internal fun colorimetry(colorInfo: androidx.media3.common.ColorInfo?): String {
    val c = colorInfo ?: return ""
    val space = when (c.colorSpace) {
        C.COLOR_SPACE_BT2020 -> "BT.2020"
        C.COLOR_SPACE_BT709 -> "BT.709"
        C.COLOR_SPACE_BT601 -> "BT.601"
        else -> return ""
    }
    val transfer = when (c.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "PQ"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR gamma"
        else -> return space
    }
    val range = when (c.colorRange) {
        C.COLOR_RANGE_FULL -> "Full"
        C.COLOR_RANGE_LIMITED -> "Limited"
        else -> return "$space · $transfer"
    }
    return "$space · $transfer · $range"
}

internal fun buildDataPoolInfo(player: ExoPlayer, meta: PlayerMeta): Map<String, String> {
    val info = mutableMapOf<String, String>()
    // Provider-classified details first (delivery mode, itag…), then probe the actual tracks.
    info.putAll(meta.streamInfo)

    player.currentTracks.groups.forEach { group ->
        if (group.type == C.TRACK_TYPE_VIDEO) {
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val f = group.getTrackFormat(i)
                    resolutionClass(f.width, f.height).takeIf { it.isNotBlank() }?.let { info["Video class"] = it }
                    videoCodecClass(f.codecs, f.sampleMimeType).takeIf { it.isNotBlank() }?.let { info["Video codec"] = it }
                    if (f.frameRate > 0) info["Frame rate"] = "${f.frameRate} fps"
                    if (f.width > 0 && f.height > 0) info["Resolution"] = "${f.width}×${f.height}"
                    if (f.bitrate > 0) info["Video bitrate"] = "${f.bitrate / 1000} kbps"
                    info["HDR class"] = hdrClass(f.colorInfo, f.codecs)
                    colorimetry(f.colorInfo).takeIf { it.isNotBlank() }?.let { info["Color"] = it }
                }
            }
        }
        if (group.type == C.TRACK_TYPE_AUDIO) {
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val f = group.getTrackFormat(i)
                    audioCodecClass(f.codecs, f.sampleMimeType).takeIf { it.isNotBlank() }?.let { info["Audio codec"] = it }
                    channelLayout(f.channelCount).takeIf { it.isNotBlank() }?.let { info["Audio layout"] = it }
                    if (f.bitrate > 0) info["Audio bitrate"] = "${f.bitrate / 1000} kbps"
                    if (f.sampleRate > 0) info["Sample rate"] = "${f.sampleRate} Hz"
                    f.language?.takeIf { it.isNotBlank() }?.let { info["Language"] = it.uppercase() }
                }
            }
        }
    }

    info["Buffered"] = deckClock(player.bufferedPosition)
    info["Speed"] = "%.2f×".format(player.playbackParameters.speed)

    val boost = AppPreferences.dialogueBoost
    if (boost > 0) info["Dialogue boost"] = DialogueBoost.levelName(boost)

    return info
}

@Composable
internal fun NextUpStrip(
    title: String,
    startMs: Long,
    durationMs: Long?,
    onPlayNext: (() -> Unit)?,
    onResetHide: () -> Unit,
    thumbUrl: String? = null,
    onSetReminder: (() -> Unit)? = null,
    reminderSet: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .padding(horizontal = ThemeSpec.Safe.horizontal, vertical = ThemeSpec.Space.xs)
            .fillMaxWidth()
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .playerGlass(RoundedCornerShape(ThemeSpec.Card.cornerLg), elevation = 16.dp)
            .clickableNoIndication(interaction) { expanded = !expanded; onResetHide() }
            .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        Box(
            Modifier.size(width = 50.dp, height = 30.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                .background(ThemeSemantics.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbUrl.isNullOrBlank()) Text("NEXT", fontSize = ThemeSpec.Type.micro, color = ThemeSemantics.textSecondary, fontWeight = FontWeight.Bold)
            else coil3.compose.AsyncImage(model = thumbUrl, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = buildList {
                if (startMs > 0L) add(hhmm(startMs))
                durationMs?.takeIf { it > 0L }?.let { add(deckClock(it)) }
            }.joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
        }
        if (expanded) {
            onPlayNext?.let { PlayerBarIconButton(PlayerGlyph.PLAY, "Play", onClick = { it(); onResetHide() }) }
            onSetReminder?.let { PlayerBarIconButton(PlayerGlyph.SCHEDULE, if (reminderSet) "Cancel reminder" else "Reminder", onClick = { it(); onResetHide() }) }
        } else {
            Text(if (focused) "OK expand" else "Next up", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, maxLines = 1)
        }
    }
}

@Composable
private fun PlayerInfoPanel(
    meta: PlayerMeta,
    onDismiss: () -> Unit,
) {
    val online by produceState<com.carteltv.tv.data.stremio.OnlineTitleInfo?>(null, meta.refId) {
        value = if (meta.isLive) null else suspendRunCatching { com.carteltv.tv.data.stremio.OnlineTitleInfoLoader.load(meta.refId) }.getOrNull()
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().dismissScrim { onDismiss() })
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = ThemeSpec.Safe.vertical)
                .padding(end = ThemeSpec.Safe.horizontal)
                .fillMaxHeight()
                .width(if (online != null) 340.dp else 160.dp)
                .playerGlass(RoundedCornerShape(ThemeSpec.Card.cornerLg), heavy = true)
                .verticalScroll(rememberScrollState())
                .padding(ThemeSpec.Space.lg),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                PlayerGlyphIcon(PlayerGlyph.INFO, ThemeSemantics.accentBright, 18.dp)
                Text("INFO", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.accentBright, fontWeight = FontWeight.Bold, letterSpacing = ThemeSpec.Type.trackCaps)
            }
            Text(meta.channelName.ifBlank { meta.title }, fontSize = ThemeSpec.Type.meta, color = PlayerChrome.ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            online?.let { OnlineInfoBlock(it) }
            if (meta.nowTitle.isNotBlank()) {
                Text("Now", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, letterSpacing = ThemeSpec.Type.trackCaps)
                Text(meta.nowTitle, fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("Up next", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, letterSpacing = ThemeSpec.Type.trackCaps)
            val queue = (meta.queueTitles.takeIf { it.isNotEmpty() } ?: buildList {
                if (meta.nextTitle.isNotBlank()) add(meta.nextTitle)
                if (meta.nextEpisodeLabel.isNotBlank() && meta.nextEpisodeLabel != meta.nextTitle) add(meta.nextEpisodeLabel)
            }).take(3)
            queue.forEach { item ->
                Text("\u2022 $item", fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (queue.isEmpty()) Text("No queue data", fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkDim)
            Spacer(Modifier.weight(1f))
            Text("Back closes", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim)
        }
    }
}

@Composable
private fun OnlineInfoBlock(info: com.carteltv.tv.data.stremio.OnlineTitleInfo) {
    (info.episodeThumb ?: info.poster)?.let { art ->
        coil3.compose.AsyncImage(
            model = art,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = (if (info.episodeThumb != null) Modifier.fillMaxWidth() else Modifier.width(110.dp))
                .aspectRatio(if (info.episodeThumb != null) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)),
        )
    }
    val facts = listOfNotNull(info.year, info.rating?.let { "\u2605 $it" }, info.runtime).joinToString("  \u00b7  ")
    if (facts.isNotBlank()) Text(facts, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.accentBright, maxLines = 1)
    if (info.genres.isNotEmpty()) Text(info.genres.joinToString(" / "), fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkDim, maxLines = 1)
    info.episodeTitle?.let { Text(it, fontSize = ThemeSpec.Type.label, color = PlayerChrome.ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    (info.episodeOverview ?: info.overview)?.let { Text(it, fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkMuted, maxLines = 8, overflow = TextOverflow.Ellipsis) }
    if (info.director.isNotEmpty()) Text("Directed by " + info.director.joinToString(", "), fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkDim, maxLines = 2)
    if (info.cast.isNotEmpty()) Text("Cast: " + info.cast.joinToString(", "), fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textTertiary, maxLines = 3, overflow = TextOverflow.Ellipsis)
}
