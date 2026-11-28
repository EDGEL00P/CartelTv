package com.carteltv.tv.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.core.suspendRunCatching
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.focusScaleState

/**
 * The countdown for what plays next, shown in a title's last seconds. One card on every player:
 * bottom-right, "Play now" focused when it appears, Back on the remote cancels it. The host decides
 * when it shows and how long it counts.
 */
@Composable
fun UpNextCard(
    title: String,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    thumbUrl: String? = null,
    startMs: Long = 0L,
    durationMs: Long = 0L,
    onSetReminder: (() -> Unit)? = null,
    overview: String? = null,
    subtitle: String? = null,
) {
    val playFocus = remember { FocusRequester() }
    var collapsed by remember { mutableStateOf(false) }
    var collapseFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { suspendRunCatching { playFocus.awaitFocus() } }
    Column(
        modifier
            .widthIn(max = 440.dp)
            // A tall card (long overview + thumb) must never run off the 540dp stage: cap it and scroll inside.
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState())
            .playerGlass(RoundedCornerShape(ThemeSpec.Card.cornerLg), heavy = true)
            .padding(ThemeSpec.Space.lg),
    ) {
        if (!thumbUrl.isNullOrBlank()) {
            coil3.compose.AsyncImage(
                model = thumbUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .padding(bottom = ThemeSpec.Space.md)
                        .clip(RoundedCornerShape(ThemeSpec.Card.corner)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            Box(
                Modifier
                    .size(width = 58.dp, height = 34.dp)
                    .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                    .background(PlayerChrome.well),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbUrl.isNullOrBlank()) {
                    Text("NEXT", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim, fontWeight = FontWeight.Bold)
                } else {
                    coil3.compose.AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Up next",
                    fontSize = ThemeSpec.Type.label,
                    fontWeight = FontWeight.Bold,
                    color = ThemeSemantics.accentBright,
                    maxLines = 1,
                )
                Text(
                    title,
                    fontSize = ThemeSpec.Type.meta,
                    color = PlayerChrome.ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${secondsLeft}s",
                    fontSize = ThemeSpec.Type.title,
                    fontWeight = FontWeight.Bold,
                    fontFamily = ThemeSpec.Font.mono,
                    color = PlayerChrome.ink,
                    maxLines = 1,
                )
                Text(
                    if (collapsed) "Expand" else "Collapse",
                    fontSize = ThemeSpec.Type.micro,
                    fontWeight = FontWeight.SemiBold,
                    color = if (collapseFocused) PlayerChrome.ink else PlayerChrome.inkDim,
                    modifier =
                        Modifier
                            .focusLift(focusScaleState(collapseFocused, ThemeSpec.Focus.liftCompact))
                            .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                            .background(if (collapseFocused) PlayerChrome.wellStrong else Color.Transparent)
                            .focusRing(collapseFocused, RoundedCornerShape(ThemeSpec.Card.cornerSm))
                            .onFocusChanged { collapseFocused = it.isFocused }
                            .clickableNoIndication { collapsed = !collapsed }
                            .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
                )
            }
        }
        if (!collapsed) {
            val detail =
                buildList {
                    if (!subtitle.isNullOrBlank()) add(subtitle)
                    if (startMs > 0L) {
                        add(
                            com.carteltv.tv.ui.live
                                .hhmm(startMs),
                        )
                    }
                    if (durationMs > 0L) add(deckClock(durationMs))
                }.joinToString(" \u00b7 ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    fontSize = ThemeSpec.Type.label,
                    color = PlayerChrome.inkMuted,
                    modifier = Modifier.padding(top = ThemeSpec.Space.sm),
                    maxLines = 1,
                )
            }
            if (!overview.isNullOrBlank()) {
                Text(
                    overview,
                    fontSize = ThemeSpec.Type.label,
                    color = PlayerChrome.inkDim,
                    modifier = Modifier.padding(top = ThemeSpec.Space.xs),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        top = ThemeSpec.Space.md,
                    ).height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PlayerChrome.track),
            ) {
                Box(Modifier.fillMaxWidth((secondsLeft / 30f).coerceIn(0f, 1f)).fillMaxHeight().background(ThemeSemantics.accent))
            }
            ActionButton(
                "Play now",
                filled = true,
                modifier = Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.md).focusRequester(playFocus),
            ) {
                onPlayNow()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                modifier = Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.xs),
            ) {
                onSetReminder?.let { set ->
                    Box(Modifier.weight(1f)) {
                        ActionButton("Set Reminder", filled = false, modifier = Modifier.fillMaxWidth()) { set() }
                    }
                }
                Box(Modifier.weight(1f)) {
                    ActionButton("Cancel", filled = false, modifier = Modifier.fillMaxWidth()) { onCancel() }
                }
            }
        }
    }
}

/**
 * What to say when a stream could not be played, the same on every player. [message] is for the
 * viewer; [detail] is the raw error code and stays out of sight until focus moves onto "Details".
 * Back closes the player from here, so the remote is not routed while this card is up.
 */
@Composable
fun PlayerErrorCard(
    message: String,
    detail: String?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Retry",
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(message) { suspendRunCatching { retryFocus.awaitFocus() } }
    Box(
        modifier.fillMaxSize().background(ThemeSemantics.scrimModal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .trapFocus()
                .widthIn(max = 680.dp)
                // On the 540dp stage a long message plus the revealed detail can overrun: cap and scroll inside.
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .playerGlass(RoundedCornerShape(ThemeSpec.Card.cornerLg), heavy = true)
                .padding(ThemeSpec.Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(ThemeSemantics.danger.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                PlayerGlyphIcon(PlayerGlyph.INFO, ThemeSemantics.danger, 26.dp)
            }
            Spacer(Modifier.height(ThemeSpec.Space.md))
            Text(
                "Unable to play this video",
                fontSize = ThemeSpec.Type.titleL,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.broadcast,
                color = PlayerChrome.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(
                message,
                fontSize = ThemeSpec.Type.meta,
                color = PlayerChrome.inkMuted,
                maxLines = 3,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                ActionButton(retryLabel, filled = true, modifier = Modifier.widthIn(min = 150.dp).focusRequester(retryFocus)) { onRetry() }
                ActionButton("Close", filled = false, modifier = Modifier.widthIn(min = 150.dp)) { onClose() }
            }
            if (detail != null) {
                var onDetails by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(ThemeSpec.Card.corner)
                Spacer(Modifier.height(ThemeSpec.Space.lg))
                // The deck's one focus treatment: surface, ring and lift. The detail text below is the focus reveal.
                Row(
                    Modifier
                        .focusLift(focusScaleState(onDetails, ThemeSpec.Focus.liftCompact))
                        .playerWell(onDetails, shape)
                        .onFocusChanged { onDetails = it.isFocused }
                        .focusable()
                        .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                ) {
                    PlayerGlyphIcon(PlayerGlyph.INFO, if (onDetails) PlayerChrome.onFocusFill else PlayerChrome.ink, 22.dp)
                    Text(
                        "Details",
                        fontSize = ThemeSpec.Type.meta,
                        fontWeight = FontWeight.Medium,
                        color = if (onDetails) PlayerChrome.onFocusFill else PlayerChrome.ink,
                    )
                }
                if (onDetails) {
                    Text(
                        detail,
                        fontSize = ThemeSpec.Type.label,
                        color = PlayerChrome.inkDim,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = ThemeSpec.Space.xs),
                    )
                }
            }
            Text(
                "Press Back to close.",
                fontSize = ThemeSpec.Type.label,
                color = PlayerChrome.inkDim,
                maxLines = 1,
                modifier = Modifier.padding(top = ThemeSpec.Space.lg),
            )
        }
    }
}

/** Where the pending seek will land: its frame when the provider has one, the time, and a thin progress line. */
@Composable
internal fun SeekHud(
    targetMs: Long,
    durationMs: Long,
    thumb: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val frac = if (durationMs > 0) (targetMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth()) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier =
                    Modifier
                        .padding(start = ThemeSpec.Safe.horizontal, bottom = ThemeSpec.Space.sm)
                        .width(248.dp)
                        .height(140.dp)
                        .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .border(1.dp, PlayerChrome.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg)),
            )
        }
        Text(
            deckClock(targetMs) + if (durationMs > 0) " / " + deckClock(durationMs) else "",
            fontSize = ThemeSpec.Type.title,
            fontWeight = FontWeight.Bold,
            color = PlayerChrome.ink,
            fontFamily = ThemeSpec.Font.mono,
            // The deck is hidden while the HUD shows, so the time sits on bare video: it carries its own glass.
            modifier =
                Modifier
                    .padding(start = ThemeSpec.Safe.horizontal, bottom = ThemeSpec.Safe.vertical + ThemeSpec.Space.xs)
                    .playerGlass(RoundedCornerShape(ThemeSpec.Card.pill), elevation = 14.dp)
                    .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = ThemeSpec.Safe.vertical)
                .height(5.dp)
                .background(PlayerChrome.track),
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(ThemeSemantics.accent))
            BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp)) {
                Box(
                    Modifier
                        .offset(x = (maxWidth - 10.dp) * frac)
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(PlayerChrome.ink),
                )
            }
        }
    }
}

/** Live only: where the viewer stands against the live edge after a left/right press. */
@Composable
internal fun LiveOffsetPill(
    behindMs: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .playerGlass(RoundedCornerShape(ThemeSpec.Card.pill), elevation = 14.dp)
            .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        if (behindMs <
            0
        ) {
            LiveDot()
        } else {
            Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(PlayerChrome.inkDim))
        }
        Text(
            if (behindMs < 0) "Watching live" else "%s behind live".format(deckClock(behindMs)),
            fontSize = ThemeSpec.Type.meta,
            fontWeight = FontWeight.Bold,
            color = PlayerChrome.ink,
            maxLines = 1,
        )
    }
}

/** Spinner and label on one glass chip: buffering has to read over a bright picture as clearly as over a dark one. */
@Composable
internal fun BufferingPill(modifier: Modifier = Modifier) {
    Row(
        modifier
            .playerGlass(RoundedCornerShape(ThemeSpec.Card.pill), elevation = 14.dp)
            .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        // tv-material 1.1.0 ships no progress indicator, so this is the Compose Material spinner the app already depends on.
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = ThemeSemantics.accentBright,
            strokeWidth = 3.dp,
        )
        Text("Buffering", fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.SemiBold, color = PlayerChrome.ink, maxLines = 1)
    }
}

/** m:ss, or h:mm:ss from an hour up; never negative. */
internal fun deckClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
