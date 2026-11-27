@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.carteltv.tv.ui.apps
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.focusRing
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.carteltv.tv.data.apps.InstalledAppsCache
import com.carteltv.tv.data.apps.AppsRepository
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.player.PlayerGlyph
import com.carteltv.tv.ui.player.PlayerGlyphIcon
import com.carteltv.tv.ui.theme.*
import kotlinx.coroutines.delay
import com.carteltv.tv.ui.components.OverlayCoordinator
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.PageLoading
import com.carteltv.tv.ui.components.rememberAppIcon
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.combinedClickableNoIndication
import com.carteltv.tv.ui.nav.PagePadding

object AllAppsOverlayState {
    var open by mutableStateOf(false)
        private set

    fun show() { if (OverlayCoordinator.canOpenTransient()) open = true }
    fun hide() { open = false }
    fun toggle() { if (open) hide() else show() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AllAppsOverlay() {
    if (!AllAppsOverlayState.open) return

    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()
    val firstItemRef = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }

    val allApps = InstalledAppsCache.apps
    val hidden = AppPreferences.hiddenApps()
    val filtered = remember(allApps, hidden, query) {
        val q = query.trim().lowercase()
        allApps
            .filter { it.packageName !in hidden }
            .filter { if (q.isBlank()) true else it.label.lowercase().contains(q) }
            .sortedBy { it.label.lowercase() }
    }

    BackHandler { AllAppsOverlayState.hide() }

    LaunchedEffect(Unit) {
        suspendRunCatching { firstItemRef.awaitFocus() }
    }

    Box(Modifier.fillMaxSize().trapFocus()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(ThemeSemantics.scrimModal)
                .dismissScrim { AllAppsOverlayState.hide() },
        )

        // Sheet — left-shelved panel, exactly four tiles wide plus the page gutter and the tile gaps.
        val sheetWidth = ThemeSpec.Safe.horizontal * 2 + 120.dp * 4 + ThemeSpec.Space.md * 3
        Column(
            Modifier
                .fillMaxHeight()
                .width(sheetWidth)
                .clip(RoundedCornerShape(topEnd = ThemeSpec.Card.cornerLg, bottomEnd = ThemeSpec.Card.cornerLg))
                .background(ThemeSemantics.surfaceRest)
                .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(topEnd = ThemeSpec.Card.cornerLg, bottomEnd = ThemeSpec.Card.cornerLg)),
        ) {
            // Header — the masthead treatment, so the sheet reads as the same chrome as the page behind it.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = ThemeSpec.Space.margin, top = ThemeSpec.Space.md, end = ThemeSpec.Safe.horizontal, bottom = ThemeSpec.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoSizeText(
                    androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.apps_all_title),
                    fontSize = ThemeSpec.Type.titleL, fontWeight = FontWeight.Bold, fontFamily = ThemeSpec.Font.display,
                    color = ThemeSemantics.textEmphatic, letterSpacing = ThemeSpec.Type.trackHeadline,
                )
                Spacer(Modifier.width(ThemeSpec.Space.cozy))
                Box(Modifier.size(6.dp).background(ThemeSemantics.accent, CircleShape))
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                Text("${filtered.size}", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textTertiary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, bottom = ThemeSpec.Space.cozy)
                    .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                    .background(ThemeSemantics.textEmphatic.copy(alpha = 0.06f))
                    .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.corner))
                    .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerGlyphIcon(PlayerGlyph.SEARCH, ThemeSemantics.textSecondary, 22.dp)
                Spacer(Modifier.width(ThemeSpec.Space.sm))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textEmphatic,
                    ),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.apps_search_hint), fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textTertiary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        innerTextField()
                    },
                )
                if (query.isNotEmpty()) {
                    val clearFocus = remember { MutableInteractionSource() }
                    val clearFocused by clearFocus.collectIsFocusedAsState()
                    Text(
                        "CLEAR", fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Black, color = ThemeSemantics.textSecondary,
                        fontFamily = ThemeSpec.Font.broadcast, letterSpacing = ThemeSpec.Type.trackCaps,
                        modifier = Modifier
                            .focusRing(clearFocused, RoundedCornerShape(ThemeSpec.Card.cornerSm))
                            .clickableNoIndication(clearFocus) { query = "" }
                            .focusable()
                            .padding(ThemeSpec.Space.xs), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }

            // App grid. An empty list only means something once the first snapshot of installed apps has landed.
            if (filtered.isEmpty() && InstalledAppsCache.version == 0) {
                PageLoading(Modifier.weight(1f))
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(
                        if (query.isNotBlank()) "No apps match \"$query\"" else "No apps installed",
                        hint = if (query.isNotBlank()) "Try a different name." else null,
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(4),
                    contentPadding = PagePadding,
                    horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                    verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                    modifier = Modifier.weight(1f).focusGroup().focusRestorer(firstItemRef),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppGridTile(
                            app = app,
                            focusRequester = if (app == filtered.firstOrNull()) firstItemRef else null,
                            onClick = {
                                AllAppsOverlayState.hide()
                                AppsRepository.launchApp(ctx, app.packageName)
                            },
                        )
                    }
                }
            }

            Text(
                "Press OK to launch. Press Back to close.",
                fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary,
                modifier = Modifier.padding(start = ThemeSpec.Space.margin, top = ThemeSpec.Space.sm, bottom = ThemeSpec.Space.cozy), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppGridTile(
    app: LauncherApp,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)

    val fm = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .then(fm)
            .width(120.dp)
            // The card surface, spelled out so the OK-press dip can ride the same lift.
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .glassSurface(focused, shape)
            .combinedClickableNoIndication(
                interactionSource = interaction,
                onClickLabel = app.label,
                onClick = onClick,
                onLongClick = {
                    val hidden = AppPreferences.hiddenApps()
                    if (app.packageName in hidden) {
                        AppPreferences.setHiddenApps(hidden - app.packageName)
                    } else {
                        AppPreferences.setHiddenApps(hidden + app.packageName)
                    }
                },
            )
            .padding(vertical = ThemeSpec.Space.cozy, horizontal = ThemeSpec.Space.sm),
    ) {
        val iconBitmap = rememberAppIcon(app)

        Box(
            Modifier
                .size(AppTileSpec.icon)
                .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                .background(ThemeSemantics.textEmphatic.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (iconBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = iconBitmap, contentDescription = "${app.label} app icon",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    app.label.take(1).uppercase(),
                    fontSize = ThemeSpec.Type.title, color = ThemeSemantics.accent, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(ThemeSpec.Space.sm))

        Text(
            app.label,
            fontSize = AppTileSpec.label, fontFamily = AppTileSpec.face, color = if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}
