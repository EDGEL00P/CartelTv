package com.carteltv.tv.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.glassFill

/**
 * The player is dark whatever the app theme is: the picture is the content and the controls sit on a
 * smoked glass shadow, not a frost. Liquid Glass geometry: layered translucency, a specular top rim and
 * a soft drop shadow, so the deck reads as a floating pane over the frame instead of an opaque slab.
 */
internal object PlayerChrome {
    val floor = Color.Black
    val ink = Color.White
    val inkMuted = Color(0xFFD3D3D9)
    val inkDim = Color(0xFF9C9CA5)
    val track = Color.White.copy(alpha = 0.20f)
    val buffered = Color.White.copy(alpha = 0.40f)
    val scrimTop = Color.Black.copy(alpha = 0.55f)
    val scrimBottom = Color.Black.copy(alpha = 0.90f)
    val chip = Color.Black.copy(alpha = 0.42f)
    val hairline = Color.White.copy(alpha = 0.14f)

    /** Glass skin: frosted pane over the frame — backdrop blur plus this tint. */
    val glassTop = Color(0x80141418)
    val glassBottom = Color(0xA608080A)
    val glassHeavyTop = Color(0x9917171C)
    val glassHeavyBottom = Color(0xB808080B)

    /** Specular rim along the top edge, fading at the sides. */
    val rim = Color.White.copy(alpha = 0.20f)
    val rimSoft = Color.White.copy(alpha = 0.07f)

    /** Quiet control wells at rest; the focused chip is a solid light pill with dark content. */
    val well = Color.White.copy(alpha = 0.08f)
    val wellStrong = Color.White.copy(alpha = 0.14f)
    val focusFill = Color.White
    val onFocusFill = Color(0xFF0B0B0D)
}

/**
 * A floating glass pane: drop shadow, translucent gradient, clipped rim light.
 *
 * Player glass never backdrop-blurs. It floats over a Media3 `PlayerView`, which renders into a
 * `SurfaceView` — a separate hardware surface Haze cannot capture (see PlayerScreen's glassState).
 * A blur source that goes stale the instant playback buffers makes the chip strobe between the live
 * capture and its fallback tint, so the player uses the smoked-tint path everywhere. The picture is
 * the content; the controls read as a smoked pane over it, not a frost of a surface we cannot see.
 */
@Composable
internal fun Modifier.playerGlass(
    shape: Shape,
    heavy: Boolean = false,
    elevation: Dp = 18.dp,
): Modifier =
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .glassFill(if (heavy) PlayerChrome.glassHeavyTop else PlayerChrome.glassTop, blur = false)
        .border(1.dp, Brush.verticalGradient(listOf(PlayerChrome.rim, PlayerChrome.rimSoft, Color.Transparent)), shape)
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(listOf(PlayerChrome.rimSoft, Color.Transparent), startY = 0f, endY = 44f),
                size = Size(size.width, 44f),
            )
        }

/**
 * The deck: a bottom sheet, not a card. It has no shadow, no outline and no corner, and its scrim
 * fades in from the picture, so the frame keeps showing through the top of the controls.
 */
@Composable
internal fun Modifier.playerDeck(): Modifier =
    this
        .background(
            Brush.verticalGradient(
                colorStops =
                    arrayOf(
                        0f to Color.Transparent,
                        0.16f to Color.Black.copy(alpha = 0.38f),
                        0.5f to Color.Black.copy(alpha = 0.76f),
                        1f to Color.Black.copy(alpha = 0.95f),
                    ),
            ),
        )

/** A small glass control well; focus turns it into the solid light pill. */
@Composable
internal fun Modifier.playerWell(
    focused: Boolean,
    shape: Shape,
): Modifier =
    this
        .clip(shape)
        .background(if (focused) PlayerChrome.focusFill else PlayerChrome.well, shape)
        .border(1.dp, if (focused) PlayerChrome.focusFill else PlayerChrome.hairline, shape)

/** A softly pulsing live dot: the only ambient motion on the deck. */
@Composable
internal fun LiveDot(modifier: Modifier = Modifier) {
    val pulse =
        rememberInfiniteTransition(label = "liveDot").animateFloat(
            initialValue = 0.40f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Reverse),
            label = "liveDotPulse",
        )
    Box(modifier.size(6.dp).clip(CircleShape).background(PlayerChrome.ink.copy(alpha = pulse.value)))
}

/** The standard deck radius: a soft 2026 pane, still quieter than the content. */
internal val deckShape: Shape get() = RoundedCornerShape(ThemeSpec.Card.cornerLg + 8.dp)

/** "1h 51m left", "42m left", "Under a minute left". */
internal fun timeLeftLabel(remainingMs: Long): String {
    val totalMinutes = (remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L
    return when {
        remainingMs <= 0L -> "Finished"
        totalMinutes <= 1L -> "Under a minute left"
        totalMinutes < 60L -> "${totalMinutes}m left"
        else -> "${totalMinutes / 60}h ${totalMinutes % 60}m left"
    }
}
