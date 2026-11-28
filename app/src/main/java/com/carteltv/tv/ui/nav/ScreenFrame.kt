package com.carteltv.tv.ui.nav
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassSurface
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The standard content padding for a dock page: one step above the first row, and a full gutter of bleed
 * below so the last row runs off screen instead of stopping short.
 */
val PagePadding =
    PaddingValues(
        start = ThemeSpec.Space.margin,
        end = ThemeSpec.Safe.horizontal,
        top = ThemeSpec.Space.md,
        bottom = ThemeSpec.Space.margin,
    )

/**
 * The one frame for every section page: backdrop, the inset below the navigation bar, then the page.
 * The bar already names the section and carries the clock and actions, so a page has no header of its own;
 * it brings its own grid or list with [PagePadding].
 */
@Composable
internal fun ScreenPage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ScreenScaffold {
        // With the global bar hidden (YouTube Music) nothing else holds the left edge off the screen, so the frame adds
        // what the page's own gutter lacks to reach the safe margin.
        val railless = if (ShellChrome.barHidden) ThemeSpec.Safe.horizontal else 0.dp
        Box(modifier.fillMaxSize().padding(start = railless)) { content() }
    }
}

/** The one filter row: chips at the page gutter, one focus group, and focus remembered when you come back. */
@Composable
internal fun PageChipRow(
    modifier: Modifier = Modifier,
    entry: androidx.compose.ui.focus.FocusRequester? = null,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier.restoreRowFocus(entry),
        contentPadding =
            PaddingValues(
                start = ThemeSpec.Space.margin,
                end = ThemeSpec.Safe.horizontal,
                top = ThemeSpec.Space.xs,
                bottom = ThemeSpec.Space.cozy,
            ),
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
        content = content,
    )
}

/**
 * Frame for every top-level section: themed backdrop and the screen below the navigation bar.
 * Content runs to the bottom edge so rows bleed off screen instead of being cut at the overscan line.
 *
 * A screen whose first row should run under an inset passes [bleedTop] or [bleedStart]: the frame then
 * leaves that edge alone, and the screen keeps the overscan as its own content padding.
 */
@Composable
internal fun ScreenScaffold(
    bleedTop: Boolean = false,
    bleedStart: Boolean = false,
    content: @Composable () -> Unit,
) {
    // The backdrop is the Haze source every glass surface in the screen frosts over. Blur is
    // disabled (tint fallback) when the user asked for reduced transparency.
    val glassState = rememberHazeState(blurEnabled = !com.carteltv.tv.ui.theme.ThemeAccessibility.reduceTransparency)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(state = glassState)) {
            com.carteltv.tv.ui.theme
                .CartelBackdrop()
        }
        Column(
            Modifier.fillMaxSize().padding(
                start = if (ShellChrome.barHidden || bleedStart) 0.dp else ShellInsetStart,
                top = if (bleedTop) 0.dp else TopBarClearance,
                bottom = 0.dp,
            ),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.carteltv.tv.ui.theme.LocalInScreenScaffold provides true,
                    com.carteltv.tv.ui.theme.LocalGlassBackdrop provides glassState,
                ) { content() }
            }
        }
    }
}

/** The card surface for rows and tiles: glass fill, standard corner, focus ring, house focus lift. */
@Composable
fun Modifier.cardSurface(
    focused: Boolean,
    lift: Float = ThemeSpec.Focus.liftCompact,
    idle: Color = Color.Transparent,
    pressed: Boolean = false,
): Modifier =
    this
        .focusLift(focusScaleState(focused, lift, pressed))
        .glassSurface(focused, RoundedCornerShape(ThemeSpec.Card.corner), idle)
