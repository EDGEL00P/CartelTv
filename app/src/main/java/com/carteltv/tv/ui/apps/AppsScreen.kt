package com.carteltv.tv.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.carteltv.tv.data.apps.InstalledAppsCache
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.compose.foundation.focusGroup
import com.carteltv.tv.ui.nav.AppOverlayHost
import com.carteltv.tv.ui.nav.HomeMenuSpec
import com.carteltv.tv.ui.nav.HomePinSpec
import com.carteltv.tv.ui.components.PageLoading
import com.carteltv.tv.ui.components.RailFocusLine
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.combinedClickableNoIndication
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.rememberAppIcon
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.appMenuSpec
import com.carteltv.tv.ui.player.PlayerGlyph
import com.carteltv.tv.ui.player.PlayerGlyphIcon
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassSurface
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** The one app-tile spec. The Apps page, the All-apps overlay and Home's Apps row all read it, so none can drift apart. */
internal object AppTileSpec {
    val icon: Dp = 60.dp
    val label: TextUnit = ThemeSpec.Type.label
    val face: FontFamily = ThemeSpec.Font.broadcast
    /** The tile itself: the icon, a step, and one line of name at the house type scale. */
    val width: Dp = 92.dp
    val height: Dp = 104.dp
}

@Composable
internal fun AppsScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val recentsHash = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // The bar's old Recents tab lives here now: the launch history RecentsOverlay reads, as a rail.
    var recents by remember { androidx.compose.runtime.mutableStateOf<List<LauncherApp>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val pkgs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.carteltv.tv.data.apps.AppLaunchHistory.recentPackages(ctx)
        }
        val known = InstalledAppsCache.apps.associateBy { it.packageName }
        recents = pkgs.mapNotNull { known[it] }
    }
    val cachedApps = InstalledAppsCache.apps
    val shelfApps = remember(cachedApps, recentsHash.value) {
        val hidden = AppPreferences.hiddenApps()
        val launchCounts = AppPreferences.launchCounts()
        cachedApps.filter { it.packageName !in hidden }
            .sortedByDescending { launchCounts[it.packageName] ?: 0 }
    }
    // The same launch counts that ordered the shelf also pick the top rail, which is always present:
    // an earned-only rail left a fresh box opening on All apps alone, which read as a missing rail.
    val launchCounts = remember(recentsHash.value) { AppPreferences.launchCounts() }
    val used = shelfApps.filter { (launchCounts[it.packageName] ?: 0) > 0 }
    val earned = used.isNotEmpty()
    /**
     * Top rail, two honest modes:
     * Earned — anything has been launched: "Most used", the top MOST_USED_TILES by count, still
     * subtracted from All apps below, exactly the behavior this page has always had.
     * Borrowed — fresh box, nothing launched: "Recently installed". LauncherApp carries only label,
     * package and icon — no firstInstallTime — and that model is not ours to extend, so real install
     * recency is unreachable here; the pick is the first MOST_USED_TILES of the existing default
     * order (the shelf's curated order: media apps first, clutter last). All apps keeps every
     * non-hidden app in this mode, so those tiles ride in both rails — accepted, because the top
     * rail is a shortcut to what a new owner reaches first, not a filter that hides tiles from
     * All apps.
     */
    val railTitle = if (earned) "Most used" else "Recently installed"
    val recentKeys = recents.orEmpty().mapTo(HashSet()) { it.packageName }
    val mostUsed = (if (earned) used else shelfApps)
        .filterNot { it.packageName in recentKeys }
        .take(MOST_USED_TILES)
    val mostUsedKeys = mostUsed.mapTo(HashSet()) { it.packageName }
    val rest = shelfApps.filter { it.packageName !in recentKeys && it.packageName !in mostUsedKeys }
    val activeMenu = remember { androidx.compose.runtime.mutableStateOf<HomeMenuSpec?>(null) }
    val activePin = remember { androidx.compose.runtime.mutableStateOf<HomePinSpec?>(null) }
    val entryTile = remember { FocusRequester() }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        ScreenPage(
            "Apps", icon = com.carteltv.tv.R.drawable.ph_squares_four, header = false,
        ) {
            // InstalledAppsCache counts finished loads, so zero means the boot load is still running.
            if (InstalledAppsCache.version == 0) {
                PageLoading()
                return@ScreenPage
            }
            // One focus line across the rails, so the focused tile parks at the same place in every row.
            RailFocusLine {
                LazyColumn(
                    contentPadding = PagePadding,
                    verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
                    modifier = Modifier.focusGroup().focusRestorer(entryTile),
                ) {
                    if (!recents.isNullOrEmpty()) item(key = "rail:recent") {
                        AppsRail(
                            title = "Recent apps", apps = recents.orEmpty(), ctx = ctx, entry = entryTile,
                            onPin = { activePin.value = it }, onMenu = { activeMenu.value = it },
                            onChanged = { recentsHash.value++ },
                        )
                    }
                    if (mostUsed.isNotEmpty()) item(key = "rail:most-used") {
                        AppsRail(
                            title = railTitle, apps = mostUsed, ctx = ctx,
                            entry = if (recents.isNullOrEmpty()) entryTile else null,
                            onPin = { activePin.value = it }, onMenu = { activeMenu.value = it },
                            onChanged = { recentsHash.value++ },
                        )
                    }
                    item(key = "rail:all-apps") {
                        AppsRail(
                            title = "Other apps", apps = rest, ctx = ctx,
                            entry = if (mostUsed.isEmpty()) entryTile else null,
                            onPin = { activePin.value = it }, onMenu = { activeMenu.value = it },
                            onChanged = { recentsHash.value++ },
                            // The rail ends where the searchable overlay takes over, for the app no rail reaches.
                            trailing = { AllAppsGridTile(onClick = { AllAppsOverlayState.show() }) },
                        )
                    }
                }
            }
        }
        AppOverlayHost(
            menu = activeMenu.value, onMenuDismiss = { activeMenu.value = null },
            pin = activePin.value, onPinClose = { activePin.value = null },
            onChanged = { recentsHash.value++ },
        )
    }
}

/** How many apps the top rail carries, earned ("Most used") or borrowed ("Recently installed"): one screenful, so it stays a rail and not a second list. */
private const val MOST_USED_TILES = 8

/**
 * One labelled rail of the Apps page: the heading, then the tiles in a row that feathers its edges and
 * remembers where focus left it. [entry] is set only on the page's first rail, whose first tile is the
 * node the shell lands focus on; [trailing] composes one extra tile at the row's end.
 */
@Composable
private fun AppsRail(
    title: String,
    apps: List<LauncherApp>,
    ctx: android.content.Context,
    entry: FocusRequester?,
    onPin: (HomePinSpec?) -> Unit,
    onMenu: (HomeMenuSpec?) -> Unit,
    onChanged: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val rowState = rememberLazyListState()
    val wrapState = rememberRailWrapState()
    val wrapFirst = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(title, Modifier.padding(top = ThemeSpec.Space.xs, bottom = ThemeSpec.Space.xs), note = apps.size.toString())
        LazyRow(
            state = rowState,
            // Re-entering the rail lands on the app focused last; the peek of the next tile dissolves at the edge.
            modifier = Modifier
                .restoreRowFocus()
                .railEdgeFade(rowState)
                .railWrapToStart(wrapState, rowState, apps.size, wrapFirst),
            // Vertical room so a lifted tile is never clipped by the row.
            contentPadding = PaddingValues(vertical = ThemeSpec.Space.md),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { idx, app ->
                val isLocked = app.packageName in AppPreferences.appPins()
                val launch: () -> Unit = {
                    AppPreferences.bumpLaunchCount(app.packageName)
                    com.carteltv.tv.data.apps.AppsRepository.launchApp(ctx, app.packageName)
                }
                AppTile(
                    app = app,
                    locked = isLocked,
                    modifier = Modifier
                        .railWrapItem(wrapState, idx)
                        .then(
                            if (idx == 0) {
                                Modifier
                                    .focusRequester(wrapFirst)
                                    .then(if (entry != null) Modifier.entryFocus(requester = entry) else Modifier)
                            } else {
                                Modifier
                            },
                        ),
                    onClick = { if (isLocked) onPin(HomePinSpec.Unlock(app, launch)) else launch() },
                    onLongClick = {
                        onMenu(
                            appMenuSpec(
                                ctx, app, isLocked, launch,
                                onRequestPin = onPin,
                                onChanged = onChanged,
                            )
                        )
                    },
                )
            }
            if (trailing != null) item(key = "all-apps") { trailing() }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TileFrame(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .size(AppTileSpec.width, AppTileSpec.height)
            // The card surface, spelled out so the OK-press dip can ride the same lift.
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .glassSurface(focused, shape)
            .combinedClickableNoIndication(
                interactionSource = interaction,
                onClick = onClick, onLongClick = onLongClick,
            )
            .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.cozy),
    ) { content(focused) }
}

@Composable
internal fun AppTile(
    app: LauncherApp,
    locked: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val icon = rememberAppIcon(app)
    TileFrame(modifier, onClick, onLongClick) { focused ->
        Box(Modifier.size(AppTileSpec.icon), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = "${app.label} app icon", modifier = Modifier.fillMaxSize())
            } else {
                Text(app.label.take(1).uppercase(), fontSize = ThemeSpec.Type.titleL, color = ThemeSemantics.accent, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                app.label, fontSize = AppTileSpec.label, fontFamily = AppTileSpec.face, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                color = if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (locked) {
                Spacer(Modifier.width(ThemeSpec.Space.xs))
                Text(
                    "PIN", fontSize = ThemeSpec.Type.label, fontWeight = FontWeight.Black, color = ThemeSemantics.textSecondary,
                    fontFamily = ThemeSpec.Font.broadcast, letterSpacing = ThemeSpec.Type.trackCaps, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AllAppsGridTile(onClick: () -> Unit) {
    TileFrame(Modifier, onClick) { focused ->
        Box(
            Modifier.size(AppTileSpec.icon).clip(RoundedCornerShape(ThemeSpec.Card.corner)).background(ThemeSemantics.textEmphatic.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            PlayerGlyphIcon(PlayerGlyph.MULTI, if (focused) ThemeSemantics.accentBright else ThemeSemantics.textSecondary, 36.dp)
        }
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Text(
            "All apps", fontSize = AppTileSpec.label, fontFamily = AppTileSpec.face, maxLines = 1,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            color = if (focused) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
        )
    }
}
