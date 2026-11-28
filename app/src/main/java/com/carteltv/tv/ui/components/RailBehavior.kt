package com.carteltv.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.launch

/** How far a row's feathered edge reaches into the viewport; the peek of the next card dissolves inside it. */
private val EDGE_FADE = ThemeSpec.Space.xl * 2

/**
 * Feathers a rail's horizontal edges so a partially visible tile dissolves into the stage instead of being
 * sliced at the viewport. Only the edge that can scroll further fades, so a row at home shows none.
 */
fun Modifier.railEdgeFade(state: LazyListState, length: Dp = EDGE_FADE): Modifier = graphicsLayer(
    compositingStrategy = CompositingStrategy.Offscreen,
).drawWithContent {
    drawContent()
    val px = length.toPx()
    if (px >= 1f && size.width > px * 2) {
        // DstIn keeps the content only where the gradient is opaque, so the stage shows through the feather.
        if (state.canScrollBackward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = px),
                size = size.copy(width = px), blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - px, endX = size.width),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - px, 0f),
                size = size.copy(width = px), blendMode = BlendMode.DstIn,
            )
        }
    }
}

/**
 * One focus line for every rail on a page: the focused card parks at [parentFraction] of the viewport,
 * the same distance in every row, the way a streaming app keeps its slider aligned while you browse.
 * Wrap the page's column of rails; nested rows inherit it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RailFocusLine(
    parentFraction: Float = 0.28f,
    childFraction: Float = 0f,
    content: @Composable () -> Unit,
) {
    val spec = remember(parentFraction, childFraction) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val initial = parentFraction * containerSize - childFraction * size
                // Never over-scroll the last card off the trailing edge just to reach the line.
                val target = if (size <= containerSize && containerSize - initial < size) containerSize - size else initial
                return offset - target
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Raises the focused card above its siblings in draw order, so its ring and lifted art are never
 * overlapped by the tile after it (tv-material's Surface does the same for its glow). Pair with
 * [com.carteltv.tv.ui.theme.focusLift]; the rise animates with the house spring.
 */
fun Modifier.focusZLift(focused: Boolean): Modifier = composed {
    val z by animateFloatAsState(if (focused) 1f else 0f, ThemeSpec.Motion.focusSpring(), label = "focusZ")
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = z) }
    }
}

/** Tracks which item in a rail currently holds focus, so the row can tell its last card from any other. */
@Stable
class RailWrapState {
    var focusedIndex by mutableIntStateOf(-1)
}

/** One per rail; pass the same instance to [railWrapItem] and [railWrapToStart]. */
@Composable
fun rememberRailWrapState(): RailWrapState = remember { RailWrapState() }

/** Records the item's index whenever it takes focus. */
fun Modifier.railWrapItem(state: RailWrapState, index: Int): Modifier =
    this.onFocusChanged { if (it.hasFocus) state.focusedIndex = index }

/**
 * RIGHT on the last card wraps to the first: the row scrolls home and [first] takes focus.
 * Every navigable rail shares this behaviour, so no row dead-ends at its own edge.
 */
@Composable
fun Modifier.railWrapToStart(
    state: RailWrapState,
    rowState: LazyListState,
    count: Int,
    first: FocusRequester,
): Modifier {
    val scope = rememberCoroutineScope()
    return this.onPreviewKeyEvent { ev ->
        val wrap = ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight &&
            count > 1 && state.focusedIndex == count - 1
        if (wrap) scope.launch { rowState.scrollToItem(0); first.awaitFocus() }
        wrap
    }
}
