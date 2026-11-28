package com.carteltv.tv.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp

/** How strongly the artwork colour holds the stage where its art ends. */
private const val AMBIENT_ALPHA = 0.32f

/** How far the wash is tempered toward its own luminance grey before it reaches the canvas. TV panels run
 *  saturated colours hot, and the colour-thief libraries reserve the muted swatch for backgrounds — the
 *  wash keeps the artwork's hue and brightness and gives up a third of its saturation. */
private const val AMBIENT_TEMPER = 0.30f

/**
 * The colour-thief canvas: the stage floor
 * plus a soft wash of the artwork colour that currently holds the banner, rising where the
 * banner's art ends and dissolving into the black floor down the screen. Drawn, not
 * photographed — one colour field, no imagery — so the shelves over it keep all their own
 * contrast, and the banner and the rows read as one canvas instead of two tiers.
 *
 * [from] is where the wash anchors: the visible foot of the banner's art, so the colour the
 * art already shows keeps flowing below it with no seam. Null [tint] leaves the plain floor.
 */
@Composable
fun AmbientStage(tint: Color?, from: Dp, modifier: Modifier = Modifier) {
    // The wash dissolves between artwork colours on the house scene curve, so the stage never snaps.
    val colour by animateColorAsState(
        targetValue = tint ?: Color.Transparent,
        animationSpec = tween(ThemeSpec.Motion.DUR_SLOW, easing = ThemeSpec.Motion.easeStandard),
        label = "ambient",
    )
    // The tempered wash: the artwork's hue and brightness, a third of its saturation given up.
    val grey = Color(colour.luminance(), colour.luminance(), colour.luminance())
    val wash = lerp(colour, grey, AMBIENT_TEMPER)
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(ThemeSemantics.stage)
                val anchor = from.toPx().coerceIn(0f, size.height)
                if (colour.alpha > 0.01f && anchor < size.height) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(wash.copy(alpha = AMBIENT_ALPHA * colour.alpha), Color.Transparent),
                            startY = anchor,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, anchor),
                        size = Size(size.width, size.height - anchor),
                    )
                }
            },
    )
}

/** Holds the artwork colours a screen publishes for its stage. [pageTint] is the page's own key art;
 *  [cardTint] is the focused card's, which steals the stage while it is held. Created by StagedScreen. */
class AmbientTintHolder {
    var pageTint by mutableStateOf<Color?>(null)
    var cardTint by mutableStateOf<Color?>(null)

    /** What the canvas washes with: the focused card's colour, else the page's own key art. */
    val tint: Color? get() = cardTint ?: pageTint
}

/** Provided by StagedScreen so its content can tint the stage with the artwork holding it. */
val LocalAmbientTint = staticCompositionLocalOf<AmbientTintHolder?> { null }
