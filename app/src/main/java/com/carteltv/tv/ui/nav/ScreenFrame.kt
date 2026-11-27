package com.carteltv.tv.ui.nav
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
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
 * The one frame for every section page: backdrop, rail inset, the masthead with useful actions, then the
 * page. A page brings its own grid or list with [PagePadding]; the frame brings the title and the fade under
 * the masthead. Pages whose content owns the top edge (the guide timeline, a full-bleed map) pass `header = false`.
 */
@Composable
internal fun ScreenPage(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    header: Boolean = true,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    ScreenScaffold {
        // With the global rail hidden (YouTube Music) nothing else holds the left edge off the screen, so the frame adds
        // what the page's own gutter lacks to reach the safe margin.
        val railless = if (ShellChrome.barHidden) ThemeSpec.Safe.horizontal else 0.dp
        Column(modifier.fillMaxSize().padding(start = railless)) {
            if (header) ScreenMasthead(title, icon, trailing = trailing)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                content()
                // A scrolling page dissolves under the masthead instead of being sliced at its rule.
                if (header) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(ThemeSpec.Space.xl)
                            .background(Brush.verticalGradient(0f to ThemeSemantics.stage, 1f to Color.Transparent)),
                    )
                }
            }
        }
    }
}

/** The one filter row: chips at the page gutter, one focus group, and focus remembered when you come back. */
@Composable
internal fun PageChipRow(
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier.restoreRowFocus(),
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
 * The top bar every full screen shares with the dock: title at the gutter inset and useful actions
 * at the right. Back stays reachable so removing per-screen headers never removes a way out.
 */
@Composable
fun ScreenMasthead(
    title: String,
    @DrawableRes icon: Int? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val inScaffold = com.carteltv.tv.ui.theme.LocalInScreenScaffold.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(
                start = ThemeSpec.Space.margin,
                end = ThemeSpec.Safe.horizontal,
                top = if (inScaffold) ThemeSpec.Space.sm else ThemeSpec.Safe.vertical,
                bottom = ThemeSpec.Space.sm,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                val tile = RoundedCornerShape(ThemeSpec.Card.corner)
                // The section's icon is also a D-pad stop that goes Home, so the header is never a dead end.
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val pressed by interaction.collectIsPressedAsState()
                Box(
                    Modifier
                        .size(64.dp)
                        .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftIcon, pressed))
                        .glassSurface(focused, tile, ThemeSemantics.hairline, halo = false)
                        .clickableNoIndication(interaction) {
                            com.carteltv.tv.nav.SectionNav
                                .go(com.carteltv.tv.nav.AppSection.HOME)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PhosphorIcon(icon, ThemeSemantics.textEmphatic, 34.dp)
                }
                Spacer(Modifier.width(ThemeSpec.Space.md))
            }
            AutoSizeText(
                title,
                fontSize = ThemeSpec.Type.titleL,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.display,
                color = ThemeSemantics.textEmphatic,
                letterSpacing = ThemeSpec.Type.trackHeadline,
                modifier = Modifier.weight(1f),
            )
            trailing()
            Spacer(Modifier.width(ThemeSpec.Space.md))
            MastheadActions()
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Box(Modifier.fillMaxWidth().height(1.dp).background(ThemeSemantics.hairline))
    }
}

/**
 * Frame for every top-level section: themed backdrop and the screen beside the sidebar.
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
    val glassState = rememberHazeState(blurEnabled = !com.carteltv.tv.ui.theme.ThemePresets.reduceTransparency)
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
