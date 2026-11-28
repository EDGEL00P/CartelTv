package com.carteltv.tv.ui.components
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.carteltv.tv.data.apps.AppIconLoader
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.ui.hero.readArt
import com.carteltv.tv.ui.theme.AmbientStage
import com.carteltv.tv.ui.theme.AmbientTintHolder
import com.carteltv.tv.ui.theme.LocalAmbientTint
import com.carteltv.tv.ui.theme.PhosphorIcon
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.controlSurface
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one chip: filters, categories, tabs and day pickers all use it, in the house control language
 * ([controlSurface]): focus is the white key light, selected is the accent fill, rest is glass with a hairline.
 * [leading] reads its ink from [LocalContentColor] so an icon follows the label through every state.
 * [compact] tightens the pill for dense strips (the A–Z line) without touching that language.
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = ThemeSemantics.controlInk(focused, selected)
    Row(
        modifier
            .widthIn(max = 300.dp)
            .controlSurface(focused, selected, pressed)
            .onFocusChanged { focused = it.isFocused }
            .clickableNoIndication(interaction, onClick)
            .padding(horizontal = if (compact) ThemeSpec.Space.sm else ThemeSpec.Space.lg, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        if (leading != null) CompositionLocalProvider(LocalContentColor provides ink, content = leading)
        AutoSizeText(
            label,
            fontSize = ThemeSpec.Type.meta,
            minScale = 0.8f,
            fontFamily = ThemeSpec.Font.broadcast,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.SemiBold,
            color = ink,
            textAlign = TextAlign.Start,
        )
        if (dot != null) Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
    }
}

/** A shelf heading: red tag, tracked display caps and a rule. One note in the quiet colour at the right. */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutoSizeText(
                title,
                fontSize = ThemeSpec.Type.title,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.display,
                color = ThemeSemantics.textEmphatic,
                letterSpacing = ThemeSpec.Type.trackHeadline,
                modifier = Modifier.weight(1f, fill = false),
                textAlign = TextAlign.Start,
                minScale = 0.6f,
            )
            if (note != null) {
                Spacer(Modifier.width(ThemeSpec.Space.cozy))
                Text(
                    note.uppercase(),
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.textTertiary,
                    fontFamily = ThemeSpec.Font.broadcast,
                    letterSpacing = ThemeSpec.Type.trackCaps,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Nothing to show: an optional vector icon, a short statement and, when there is something to do about it, a hint beneath. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    @DrawableRes icon: Int? = null,
) {
    PageStateContent(title = title, modifier = modifier, hint = hint, icon = icon)
}

/** White or near-black, whichever reads better on [accent]: the house pick for text on accent-class fills. */
fun onAccent(accent: Color): Color = if (accent.luminance() > 0.35f) ThemeSemantics.onMediaInk else ThemeSemantics.onMedia

/** A full screen on the themed stage, so every screen shares Home's backdrop instead of sitting on flat black.
 [section] tints the backdrop with that dock section's hue, so Sports, Movies and Live read apart.
 The stage also washes — the full colour-thieving behaviour: a focused poster steals the canvas with its
 own artwork colour through the same channel Home uses, and the page's key art (set on
 [LocalAmbientTint]) holds the wash when nothing is focused. */
@Composable
fun StagedScreen(
    section: com.carteltv.tv.nav.AppSection? = null,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val ambient = remember { AmbientTintHolder() }
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf<TitleFocus?>(null) }
    // Cards report focus exactly as they do on Home; the stage washes from the focused art. Reads are
    // cached and 24px small, so browsing a grid never pays twice for the same poster.
    LaunchedEffect(focused?.art) {
        ambient.cardTint = focused?.art?.takeIf { it.isNotBlank() }?.let { readArt(ctx, it)?.tint }
    }
    val glassState = com.carteltv.tv.ui.theme.LocalGlassBackdrop.current
    Box(modifier.fillMaxSize()) {
        // The ambient wash joins the backdrop source, so glass over a staged screen frosts
        // the artwork colour as well as the scaffold's preset glows.
        Box(Modifier.fillMaxSize().then(if (glassState != null) Modifier.hazeSource(state = glassState) else Modifier)) {
            AmbientStage(ambient.tint, from = 0.dp)
        }
        CompositionLocalProvider(
            LocalAmbientTint provides ambient,
            LocalTitleFocus provides { focused = it },
        ) {
            Column(Modifier.fillMaxSize(), content = content)
        }
    }
}

/** Decodes a launcher icon off the main thread; the byte-budgeted [AppIconLoader] cache makes repeats free. */
@Composable
fun rememberAppIcon(app: LauncherApp): ImageBitmap? {
    val icon by produceState<ImageBitmap?>(null, app.packageName, app.iconPng.size) {
        value = withContext(Dispatchers.IO) { runCatching { AppIconLoader.get(app) }.getOrNull() }
    }
    return icon
}
