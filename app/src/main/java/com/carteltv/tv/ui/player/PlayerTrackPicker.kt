package com.carteltv.tv.ui.player
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.tv.material3.Text
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.live.hhmm
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import kotlinx.coroutines.delay

/** The deck leaves itself on screen for this long after the last key. */
@Composable
internal fun TrackPickerOverlay(
    player: ExoPlayer,
    trackType: TrackType,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val listState = rememberLazyGridState()

    // ExoPlayer can discover HLS subtitle tracks after playback starts, so follow the live list
    // instead of freezing it when the picker opens.
    var currentTracks by remember(player) { mutableStateOf(player.currentTracks) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onTracksChanged(updated: Tracks) {
                    currentTracks = updated
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val options = remember(currentTracks, trackType) { getTrackList(currentTracks, trackType) }
    val focusIdx = options.indexOfFirst { it.isSelected }.coerceAtLeast(0)
    val available = options.count { !it.isOff }
    val recommended =
        remember(options, trackType) {
            recommendedRow(
                options,
                trackType,
                java.util.Locale
                    .getDefault()
                    .language,
            )
        }

    BackHandler { onDismiss() }
    LaunchedEffect(Unit) {
        if (focusIdx > 0 && focusIdx < options.size) listState.scrollToItem(focusIdx)
        suspendRunCatching { focus.awaitFocus() }
    }

    Box(
        Modifier.fillMaxSize().background(ThemeSemantics.scrimModal).dismissScrim { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .trapFocus()
                .widthIn(max = 620.dp)
                .fillMaxHeight(0.8f)
                .playerGlass(RoundedCornerShape(ThemeSpec.Card.cornerLg), heavy = true)
                .padding(ThemeSpec.Space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                PlayerGlyphIcon(
                    if (trackType ==
                        TrackType.AUDIO
                    ) {
                        PlayerGlyph.VOICE
                    } else {
                        PlayerGlyph.SUBS
                    },
                    ThemeSemantics.accentBright,
                    22.dp,
                )
                Text(
                    if (trackType == TrackType.AUDIO) "Audio tracks" else "Subtitles",
                    fontSize = ThemeSpec.Type.title,
                    fontWeight = FontWeight.Bold,
                    fontFamily = ThemeSpec.Font.broadcast,
                    color = PlayerChrome.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (available == 0) "NONE" else "$available",
                    fontSize = ThemeSpec.Type.micro,
                    fontWeight = FontWeight.Bold,
                    fontFamily = ThemeSpec.Font.mono,
                    color = PlayerChrome.inkDim,
                    modifier = Modifier.playerWell(false, CircleShape).padding(horizontal = ThemeSpec.Space.md, vertical = 3.dp),
                    maxLines = 1,
                )
            }
            Text(
                if (available == 0) "No options in this stream." else "Press OK to select.",
                fontSize = ThemeSpec.Type.label,
                color = PlayerChrome.inkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            Box(Modifier.fillMaxWidth().height(1.dp).background(PlayerChrome.hairline))
            Spacer(Modifier.height(ThemeSpec.Space.sm))

            if (available == 0) {
                Text(
                    if (trackType == TrackType.SUBTITLE) {
                        "No subtitle tracks were found in this stream."
                    } else {
                        "No audio tracks were found in this stream."
                    },
                    fontSize = ThemeSpec.Type.meta,
                    color = PlayerChrome.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    state = listState,
                    // Only the list flexes, so the header and footer stay on-screen and the grid scrolls within.
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
                ) {
                    itemsIndexed(
                        options,
                        key = { _, track -> "${track.groupIndex}_${track.trackIndex}_${track.isOff}" },
                        contentType = { _, _ -> "track" },
                    ) { idx, track ->
                        TrackRowItem(
                            track = track,
                            focus = focus,
                            focusHere = idx == focusIdx,
                            recommended = track == recommended,
                            onClick = {
                                selectTrack(player, trackType, track)
                                onDismiss()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            Text(
                "Press Back to close.",
                fontSize = ThemeSpec.Type.label,
                color = PlayerChrome.inkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun TrackRowItem(
    track: TrackRow,
    focus: FocusRequester,
    focusHere: Boolean,
    recommended: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    val content = if (focused) PlayerChrome.onFocusFill else PlayerChrome.ink
    Row(
        Modifier
            .fillMaxWidth()
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .clip(shape)
            .background(
                if (focused) {
                    PlayerChrome.focusFill
                } else if (track.isSelected) {
                    ThemeSemantics.accent.copy(alpha = 0.16f)
                } else {
                    PlayerChrome.well
                },
            ).border(
                1.dp,
                if (focused) {
                    PlayerChrome.focusFill
                } else if (track.isSelected) {
                    ThemeSemantics.accent.copy(alpha = 0.55f)
                } else {
                    PlayerChrome.hairline
                },
                shape,
            ).then(if (focusHere) Modifier.focusRequester(focus) else Modifier)
            .clickableNoIndication(interaction) { onClick() }
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (track.isSelected) {
                // Radio selection: a filled dot in the ring instead of a glyph.
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (focused) content else ThemeSemantics.accent))
            } else {
                Box(Modifier.size(12.dp).border(2.dp, if (focused) content.copy(alpha = 0.6f) else PlayerChrome.inkDim, CircleShape))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontSize = ThemeSpec.Type.meta,
                fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Medium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.detail,
                fontSize = ThemeSpec.Type.label,
                color = if (focused) content.copy(alpha = 0.72f) else PlayerChrome.inkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        (if (recommended && !track.isSelected) listOf("BEST MATCH") else emptyList()).plus(track.badges).forEach { badge ->
            Text(
                badge,
                fontSize = ThemeSpec.Type.micro,
                fontWeight = FontWeight.Bold,
                color = if (focused) content else ThemeSemantics.accentBright,
                modifier =
                    Modifier
                        .border(
                            1.dp,
                            (if (focused) content else ThemeSemantics.accent).copy(alpha = 0.5f),
                            RoundedCornerShape(ThemeSpec.Card.cornerSm),
                        ).padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
                maxLines = 1,
            )
        }
    }
}

internal fun getTrackList(
    tracks: Tracks,
    type: TrackType,
): List<TrackRow> {
    val mediaType = if (type == TrackType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    val found = mutableListOf<TrackRow>()
    tracks.groups.forEachIndexed { groupIdx, group ->
        if (group.type != mediaType) return@forEachIndexed
        for (trackIdx in 0 until group.length) {
            val info = describeTrack(group.getTrackFormat(trackIdx), type)
            found +=
                TrackRow(
                    title = info.title,
                    detail = info.detail,
                    badges = info.badges,
                    isSelected = group.isTrackSelected(trackIdx),
                    groupIndex = groupIdx,
                    trackIndex = trackIdx,
                    language = group.getTrackFormat(trackIdx).language,
                    channels = group.getTrackFormat(trackIdx).channelCount.coerceAtLeast(0),
                )
        }
    }
    val rows = sortRows(found).toMutableList()
    if (type == TrackType.SUBTITLE) {
        val isDisabled =
            tracks.groups.none { g ->
                g.type == mediaType && (0 until g.length).any { g.isTrackSelected(it) }
            }
        rows.add(0, TrackRow("Off", "Turn subtitles off", emptyList(), isDisabled, -1, -1, isOff = true))
    }
    return rows
}

internal fun selectTrack(
    player: ExoPlayer,
    type: TrackType,
    track: TrackRow,
) {
    val mediaType = if (type == TrackType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    if (track.groupIndex < 0) {
        // "Off" selection
        if (type == TrackType.SUBTITLE) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(mediaType, true)
                    .build()
        }
        return
    }
    if (type == TrackType.SUBTITLE) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(mediaType, false)
                .build()
    }
    val group = player.currentTracks.groups.getOrNull(track.groupIndex) ?: return
    val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
    player.trackSelectionParameters =
        player.trackSelectionParameters
            .buildUpon()
            .addOverride(override)
            .build()
}
