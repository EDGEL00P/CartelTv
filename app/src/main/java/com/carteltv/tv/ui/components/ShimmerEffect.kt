package com.carteltv.tv.ui.components
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.carteltv.tv.ui.theme.ThemeAccessibility
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

private const val SWEEP_MS = 1400

/** A soft highlight sweeping across a placeholder; the sweep is read in the draw phase, so nothing recomposes. */
@Composable
internal fun Modifier.shimmer(): Modifier {
    // System "remove animations": one static highlight instead of the sweep, so a placeholder still reads as loading.
    if (ThemeAccessibility.systemAnimationsRemoved) return drawWithContent { drawContent(); highlight(0.35f) }
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    return drawWithContent { drawContent(); highlight(x) }
}

/** The sweep's soft diagonal band, drawn at [x] fractions of the width (−1..2 while animating). */
private fun DrawScope.highlight(x: Float) {
    val w = size.width
    val start = x * w
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, ThemeSemantics.textEmphatic.copy(alpha = 0.07f), Color.Transparent),
            start = Offset(start, 0f), end = Offset(start + w * 0.5f, size.height * 0.4f),
        ),
    )
}

/** A game card's shape while its scores load: a block where the bug goes and two short lines of text. */
@Composable
internal fun GameCardSkeleton(width: Dp = 300.dp) {
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    Box(Modifier.width(width).clip(shape).background(ThemeSemantics.surfaceRest).shimmer()) {
        androidx.compose.foundation.layout.Column {
            Box(Modifier.fillMaxWidth().height(116.dp).background(ThemeSemantics.surfaceRaised))
            androidx.compose.foundation.layout.Column(
                Modifier.padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.cozy),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) {
                Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(ThemeSemantics.surfaceRaised))
                Box(Modifier.fillMaxWidth(0.85f).height(12.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(ThemeSemantics.surfaceRaised))
            }
        }
    }
}

/** Channel-list rows while the list loads: a logo block and two lines each, one shared sweep across all of them. */
@Composable
internal fun RowsSkeleton(rows: Int = 6, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier.padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Space.sm, bottom = ThemeSpec.Space.sm).shimmer(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        repeat(rows) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(ThemeSpec.Card.corner)).background(ThemeSemantics.surfaceRest).padding(ThemeSpec.Space.cozy),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ThemeSpec.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(ThemeSemantics.surfaceRaised))
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    Box(Modifier.width(260.dp).height(14.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(ThemeSemantics.surfaceRaised))
                    Box(Modifier.width(420.dp).height(11.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(ThemeSemantics.surfaceRaised))
                }
            }
        }
    }
}
