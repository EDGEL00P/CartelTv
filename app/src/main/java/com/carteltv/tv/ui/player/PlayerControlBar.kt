package com.carteltv.tv.ui.player
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.live.hhmm
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import kotlinx.coroutines.delay

/** How far one scrubber press moves the picture. */
private const val SCRUB_STEP_MS = 10_000L

/**
 * The deck: a floating glass pane with the transport on the left and the title, programme and quality
 * context above it. The scrubber is its own focus target; while it has focus left/right scrub and OK
 * toggles playback. The settings tiles open a second, taller state of the same pane.
 */
@Composable
internal fun PlayerControlBar(
    player: ExoPlayer,
    meta: PlayerMeta,


    onPlayPause: () -> Unit,


    onSettings: () -> Unit,
    onInfo: () -> Unit,
    onClose: () -> Unit,
    onShowTracks: (TrackType) -> Unit,
    onShowDataPool: () -> Unit,
    onCycleAspect: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleBoost: () -> Unit,
    onCycleSubScale: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onOpenSidePanel: (() -> Unit)? = null,
    onOpenSchedule: (() -> Unit)? = null,
    onSourceSwitch: (() -> Unit)? = null,
    onMultiview: (() -> Unit)? = null,
    onOpenPictureInPicture: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onToggleComments: (() -> Unit)? = null,
    commentsActive: Boolean = false,
    onLastChannel: (() -> Unit)? = null,
    onResetHide: () -> Unit,
    refocus: Int,
    expanded: Boolean,
    aspectName: String,
    currentSpeed: Float,
    boostLevel: Int,
    subScale: Int,
    /** File chapter starts, drawn as ticks on the scrubber. */
    chapters: List<Long> = emptyList(),
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(refocus) { suspendRunCatching { playFocus.awaitFocus() } }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var buffered by remember { mutableLongStateOf(player.bufferedPosition.coerceAtLeast(0L)) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = runCatching { player.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
            duration = runCatching { player.duration }.getOrDefault(0L).coerceAtLeast(0L)
            buffered = runCatching { player.bufferedPosition }.getOrDefault(0L).coerceAtLeast(0L)
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (meta.nextEpisodeLabel.isNotBlank() || meta.nextTitle.isNotBlank()) {
            val nextOnline by produceState<com.carteltv.tv.data.stremio.OnlineTitleInfo?>(null, meta.nextRefId) {
                value = if (meta.nextRefId.isBlank()) null
                else suspendRunCatching { com.carteltv.tv.data.stremio.OnlineTitleInfoLoader.load(meta.nextRefId) }.getOrNull()
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val alertTitle = "${meta.channelName} \u00b7 ${meta.nextTitle}"
            var alerted by remember(alertTitle) { mutableStateOf(com.carteltv.tv.alert.KickoffAlertScheduler.hasAlert(ctx, "guide", alertTitle)) }
            val canRemind = meta.isLive && meta.nextTitle.isNotBlank() && meta.nextStartMs > System.currentTimeMillis() && meta.streamUrl.isNotBlank()
            NextUpStrip(
                reminderSet = alerted,
                onSetReminder = if (!canRemind) null else {
                    {
                        val scheduler = com.carteltv.tv.alert.KickoffAlertScheduler
                        if (alerted) {
                            scheduler.cancelAlert(ctx, "guide", alertTitle)
                            alerted = false
                            com.carteltv.tv.ui.components.TransientStatus.show("Reminder cancelled")
                        } else if (scheduler.armAlert(ctx, "guide", alertTitle, meta.nextStartMs, null, meta.streamUrl, meta.channelName)) {
                            alerted = true
                            com.carteltv.tv.ui.components.TransientStatus.show("Reminder set for ${com.carteltv.tv.ui.live.hhmm(meta.nextStartMs)}")
                        } else {
                            com.carteltv.tv.ui.components.TransientStatus.show("Too close to the start to set a reminder")
                        }
                    }
                },
                thumbUrl = nextOnline?.episodeThumb,
                title = meta.nextEpisodeLabel.ifBlank { meta.nextTitle },
                startMs = meta.nextStartMs,
                durationMs = null,
                onPlayNext = onPlayNext,
                onResetHide = onResetHide,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .playerDeck()
                .padding(
                    start = ThemeSpec.Safe.horizontal,
                    end = ThemeSpec.Safe.horizontal,
                    top = ThemeSpec.Space.sm,
                    bottom = ThemeSpec.Safe.vertical + if (expanded) ThemeSpec.Space.sm else 0.dp,
                ),
        ) {
            if (meta.isLive) {
                LiveNowNextInfo(meta, nowMs)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = ThemeSpec.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                ) {
                    if (meta.channelNumber > 0) {
                        Text(
                            "CHANNEL ${meta.channelNumber}",
                            fontSize = ThemeSpec.Type.micro,
                            color = PlayerChrome.inkDim,
                            fontFamily = ThemeSpec.Font.mono,
                            maxLines = 1,
                        )
                    }
                    Text(
                        meta.channelName.ifBlank { meta.title },
                        fontSize = ThemeSpec.Type.cardTitle,
                        fontWeight = FontWeight.Bold,
                        color = PlayerChrome.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        if (meta.subtitle.isNotBlank()) {
                            Text(
                                meta.subtitle.uppercase(),
                                fontSize = ThemeSpec.Type.micro,
                                letterSpacing = ThemeSpec.Type.trackCaps,
                                color = PlayerChrome.inkDim,
                                maxLines = 1,
                            )
                        }
                        Text(
                            meta.title.ifBlank { meta.channelName },
                            fontSize = ThemeSpec.Type.cardTitle,
                            fontWeight = FontWeight.Bold,
                            color = PlayerChrome.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (duration > 0L) {
                        val leftMs = ((duration - position) / currentSpeed.coerceAtLeast(0.25f)).toLong()
                        Text(
                            "ends ${hhmm(System.currentTimeMillis() + leftMs)}",
                            fontSize = ThemeSpec.Type.label,
                            color = PlayerChrome.inkMuted,
                            maxLines = 1,
                            modifier = Modifier.padding(start = ThemeSpec.Space.md),
                        )
                    }
                }
                if (duration > 0L) {
                    PlayerScrubber(
                        position = position,
                        duration = duration,
                        buffered = buffered,
                        chapters = chapters,
                        onSeek = { target ->
                            player.seekTo(target.coerceIn(0L, duration))
                            onResetHide()
                        },
                        onTogglePlay = onPlayPause,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) {
                PlayerBarIconButton(glyph = PlayerGlyph.INFO, label = "Info", onClick = onInfo)
                if (meta.isLive && onLastChannel != null) {
                    PlayerBarIconButton(glyph = PlayerGlyph.HISTORY, label = "Last channel", onClick = { onLastChannel(); onResetHide() })
                }

                Spacer(Modifier.weight(1f))
                PlayerPrimaryButton(
                    playing = isPlaying,
                    modifier = Modifier.focusRequester(playFocus),
                    onClick = onPlayPause,
                )
                if (!meta.isLive) {
                    PlayerBarIconButton(
                        glyph = PlayerGlyph.BACK,
                        label = "Back 10s",
                        onClick = { player.seekBack(); onResetHide() },
                    )
                    PlayerBarIconButton(
                        glyph = PlayerGlyph.FORWARD,
                        label = "Forward 10s",
                        onClick = { player.seekForward(); onResetHide() },
                    )
                }
                onPlayNext?.let {
                    PlayerBarIconButton(glyph = PlayerGlyph.SKIP_NEXT, label = "Next", onClick = it)
                }
                Spacer(Modifier.weight(1f))

                if (!meta.isLive) {
                    PlayerBarIconButton(glyph = PlayerGlyph.VOICE, label = "Audio", onClick = { onShowTracks(TrackType.AUDIO) })
                    PlayerBarIconButton(glyph = PlayerGlyph.SUBS, label = "Subtitles", onClick = { onShowTracks(TrackType.SUBTITLE) })
                }
                PlayerBarIconButton(glyph = PlayerGlyph.MORE, label = "Settings", active = expanded, onClick = onSettings)
                if (onToggleComments != null) {
                    PlayerBarIconButton(glyph = PlayerGlyph.INFO, label = "Comments", active = commentsActive, onClick = { onToggleComments(); onResetHide() })
                }
                PlayerBarIconButton(glyph = PlayerGlyph.HOME, label = "Close", onClick = onClose)
            }

            if (expanded) {
                PlayerSettingsTiles(
                    onShowTracks = onShowTracks,
                    onShowDataPool = onShowDataPool,
                    onCycleAspect = onCycleAspect,
                    onCycleSpeed = onCycleSpeed,
                    onCycleBoost = onCycleBoost,
                    onCycleSubScale = onCycleSubScale,
                    onOpenSidePanel = onOpenSidePanel,
                    onOpenSchedule = onOpenSchedule,
                    onSourceSwitch = onSourceSwitch,
                    onMultiview = onMultiview,
                    onOpenPictureInPicture = onOpenPictureInPicture,
                    onSearch = onSearch,
                    aspectName = aspectName,
                    currentSpeed = currentSpeed,
                    boostLevel = boostLevel,
                    subScale = subScale,
                    isLive = meta.isLive,
                )
            }
        }
    }
}

/** Live: what is on with its time and progress, and what is next. */
@Composable
private fun LiveNowNextInfo(meta: PlayerMeta, nowMs: Long) {
    val currentTitle = meta.nowTitle.ifBlank { meta.title.ifBlank { meta.channelName } }
    Column(Modifier.fillMaxWidth().padding(bottom = ThemeSpec.Space.xs)) {
        if (currentTitle.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (meta.nowTitle.isNotBlank()) "NOW" else "PLAYING",
                    fontSize = ThemeSpec.Type.micro,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ThemeSpec.Type.trackCaps,
                    color = ThemeSemantics.accentBright,
                    modifier = Modifier.width(46.dp),
                    maxLines = 1,
                )
                Text(currentTitle, fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.SemiBold, color = PlayerChrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (meta.nowStartMs > 0L && meta.nowStopMs > meta.nowStartMs) {
                    Text("${hhmm(meta.nowStartMs)} \u2013 ${hhmm(meta.nowStopMs)}", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, fontFamily = ThemeSpec.Font.mono, maxLines = 1)
                }
            }
            if (meta.nowStopMs > meta.nowStartMs) {
                Box(Modifier.padding(top = 3.dp, bottom = ThemeSpec.Space.xs).fillMaxWidth().height(3.dp).clip(CircleShape).background(PlayerChrome.track)) {
                    Box(Modifier.fillMaxWidth(programmeProgress(nowMs, meta.nowStartMs, meta.nowStopMs)).fillMaxHeight().background(ThemeSemantics.accent))
                }
            }
        }
        if (meta.nextTitle.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT", fontSize = ThemeSpec.Type.micro, fontWeight = FontWeight.Bold, letterSpacing = ThemeSpec.Type.trackCaps, color = PlayerChrome.inkDim, modifier = Modifier.width(46.dp), maxLines = 1)
                Text(meta.nextTitle, fontSize = ThemeSpec.Type.label, color = PlayerChrome.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (meta.nextStartMs > 0L) {
                    Text(hhmm(meta.nextStartMs), fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, fontFamily = ThemeSpec.Font.mono, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The scrubber. A progress track with buffered lane and chapter ticks, a bright knob, and time on both
 * sides. Focus widens the track and the knob; left/right move by ten seconds and OK toggles playback.
 */
@Composable
internal fun PlayerScrubber(
    position: Long,
    duration: Long,
    buffered: Long,
    chapters: List<Long>,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufFrac = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val trackHeight by animateDpAsState(if (focused) 7.dp else 4.dp, label = "scrubTrack")
    val knob by animateDpAsState(if (focused) 14.dp else 9.dp, label = "scrubKnob")

    // A run of presses adds up from the point the scrubber was focused at, so held right is not stuck
    // re-seeking the same second while the player clock catches up.
    var target by remember { mutableLongStateOf(position.coerceAtLeast(0L)) }
    LaunchedEffect(focused) { if (focused) target = position }
    LaunchedEffect(position, focused) { if (!focused) target = position }

    fun step(direction: Int) {
        if (duration <= 0L) return
        target = (target + direction * SCRUB_STEP_MS).coerceIn(0L, duration)
        onSeek(target)
    }

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> { step(-1); true }
                        Key.DirectionRight -> { step(1); true }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> { onTogglePlay(); true }
                        else -> false
                    }
                }
                .clickableNoIndication(interaction) { onTogglePlay() },
            contentAlignment = Alignment.CenterStart,
        ) {
            val barWidth = maxWidth
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(PlayerChrome.track),
            ) {
                Box(Modifier.fillMaxWidth(bufFrac).fillMaxHeight().background(PlayerChrome.buffered))
                chapters.forEach { start ->
                    if (duration <= 0L) return@forEach
                    val at = (start.toFloat() / duration).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .offset(x = (barWidth - 2.dp) * at)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(PlayerChrome.ink.copy(alpha = 0.55f)),
                    )
                }
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(ThemeSemantics.accent))
            }
            Box(
                Modifier
                    .offset(x = (barWidth - knob) * frac)
                    .size(knob)
                    .shadow(if (focused) 12.dp else 0.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(if (focused) PlayerChrome.focusFill else PlayerChrome.ink)
                    .border(2.dp, ThemeSemantics.accent, CircleShape),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(deckClock(position), fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted, fontFamily = ThemeSpec.Font.mono, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                if (focused) "Left / Right to seek \u00b7 OK plays or pauses" else timeLeftLabel(duration - position),
                fontSize = ThemeSpec.Type.micro,
                color = PlayerChrome.inkDim,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(deckClock(duration), fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted, fontFamily = ThemeSpec.Font.mono, maxLines = 1)
        }
    }
}

/** The transport's one filled target: play/pause. */
@Composable
private fun PlayerPrimaryButton(
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(40.dp)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftIcon, pressed))
            .shadow(if (focused) 10.dp else 0.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(if (focused) PlayerChrome.focusFill else ThemeSemantics.accent)
            .clickableNoIndication(interaction) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        PlayerGlyphIcon(if (playing) PlayerGlyph.PAUSE else PlayerGlyph.PLAY, if (focused) PlayerChrome.onFocusFill else PlayerChrome.ink, 22.dp)
    }
}

@Composable
internal fun PlayerBarIconButton(
    glyph: PlayerGlyph,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    alwaysLabel: Boolean = false,
    /** Previews and screenshots render the focus look without a real focus owner. */
    focusedOverride: Boolean? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focusState by interaction.collectIsFocusedAsState()
    val focused = focusedOverride ?: focusState
    val pressed by interaction.collectIsPressedAsState()
    val content = if (focused) PlayerChrome.onFocusFill else PlayerChrome.ink
    Row(
        modifier
            .height(36.dp)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .then(
                if (focused) Modifier.shadow(10.dp, CircleShape, clip = false)
                else Modifier,
            )
            .clip(CircleShape)
            .background(if (focused) PlayerChrome.focusFill else if (active) ThemeSemantics.accent.copy(alpha = 0.32f) else PlayerChrome.well)
            .border(1.dp, if (focused) PlayerChrome.focusFill else if (active) ThemeSemantics.accent.copy(alpha = 0.65f) else PlayerChrome.hairline, CircleShape)
            .semantics { contentDescription = label }
            .clickableNoIndication(interaction) { onClick() }
            .padding(horizontal = if (focused || alwaysLabel) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        PlayerGlyphIcon(glyph, content, 19.dp)
        if (focused || active || alwaysLabel) {
            Text(label, fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1)
        }
    }
}

private data class QuickControl(
    val glyph: PlayerGlyph,
    val title: String,
    val detail: String,
    val onClick: () -> Unit,
)

@Composable
internal fun PlayerSettingsTiles(
    onShowTracks: (TrackType) -> Unit,
    onShowDataPool: () -> Unit,
    onCycleAspect: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleBoost: () -> Unit,
    onCycleSubScale: () -> Unit,
    onOpenSidePanel: (() -> Unit)?,
    onOpenSchedule: (() -> Unit)?,
    onSourceSwitch: (() -> Unit)?,
    onMultiview: (() -> Unit)?,
    onOpenPictureInPicture: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    aspectName: String,
    currentSpeed: Float,
    boostLevel: Int,
    subScale: Int,
    isLive: Boolean,
) {
    val controls = buildList {
        add(QuickControl(PlayerGlyph.AUDIO, "Audio", "Lang") { onShowTracks(TrackType.AUDIO) })
        add(QuickControl(PlayerGlyph.SUBS, "Subtitles", "") { onShowTracks(TrackType.SUBTITLE) })
        add(QuickControl(PlayerGlyph.ASPECT, "Display", aspectName, onCycleAspect))
        if (!isLive) {
            val speed = "%.2f".format(currentSpeed).trimEnd('0').trimEnd('.') + "x"
            add(QuickControl(PlayerGlyph.SPEED, "Speed", speed, onCycleSpeed))
        }
        add(QuickControl(PlayerGlyph.VOICE, "Dialogue", DialogueBoost.levelName(boostLevel), onCycleBoost))
        add(QuickControl(PlayerGlyph.SUBS, "Subs size", "$subScale%", onCycleSubScale))
        add(QuickControl(PlayerGlyph.INFO, "Stats", "", onShowDataPool))
        onSourceSwitch?.let { add(QuickControl(PlayerGlyph.SOURCES, "Sources", "Src", it)) }
        onMultiview?.let { add(QuickControl(PlayerGlyph.MULTI, "Multiview", "", it)) }
        onOpenPictureInPicture?.let { add(QuickControl(PlayerGlyph.PLAY, "PiP", "Video", it)) }
        onSearch?.let { add(QuickControl(PlayerGlyph.SEARCH, "Search", "Find", it)) }
        if (isLive) {
            onOpenSchedule?.let { add(QuickControl(PlayerGlyph.SCHEDULE, "Schedule", "EPG", it)) }
        }
        onOpenSidePanel?.let { add(QuickControl(PlayerGlyph.PANEL, if (isLive) "Channels" else "Queue", "", it)) }
    }
    val rows = (controls.size + 6) / 7
    Column(Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.sm), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PLAYER SETTINGS",
                fontSize = ThemeSpec.Type.micro,
                fontWeight = FontWeight.Bold,
                letterSpacing = ThemeSpec.Type.trackCaps,
                color = ThemeSemantics.accentBright,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text("OK to change \u00b7 Back to close", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, maxLines = 1)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth().height(40.dp * rows + ThemeSpec.Space.sm * (rows - 1)),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            userScrollEnabled = false,
        ) {
            items(controls) { control -> QuickControlTile(control) }
        }
    }
}

@Composable
private fun QuickControlTile(control: QuickControl) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    val content = if (focused) PlayerChrome.onFocusFill else PlayerChrome.ink
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .clip(shape)
            .background(if (focused) PlayerChrome.focusFill else PlayerChrome.well)
            .border(1.dp, if (focused) PlayerChrome.focusFill else PlayerChrome.hairline, shape)
            .clickableNoIndication(interaction) { control.onClick() }
            .padding(horizontal = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
    ) {
        PlayerGlyphIcon(control.glyph, if (focused) content else ThemeSemantics.accentBright, 16.dp)
        Text(control.title, fontSize = ThemeSpec.Type.micro, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(control.detail, fontSize = ThemeSpec.Type.micro, color = if (focused) content.copy(alpha = 0.70f) else PlayerChrome.inkDim, maxLines = 1)
    }
}
