package com.carteltv.tv.ui.settings

import com.carteltv.tv.ui.components.TransientStatus
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.ThemeSemantics
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.update.ReleaseUpdater
import com.carteltv.tv.data.youtube.YouTubeCategoryPresets
import com.carteltv.tv.engine.stability.NotificationMirror
import com.carteltv.tv.data.trakt.TraktAuth
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.system.SleepTimerState
import com.carteltv.tv.ui.theme.*
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.components.TvTextField
import com.carteltv.tv.ui.components.OverlayCoordinator
import com.carteltv.tv.data.events.Holidays
import com.carteltv.tv.ui.home.NowOn
import com.carteltv.tv.ui.nav.ScreenMasthead
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableTv
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.components.glassRowDim
import com.carteltv.tv.ui.components.glassRowText

internal enum class SettingsPage {
    ROOT, PLAYBACK, PROVIDER, LAUNCHER, YOUTUBE, RECORDINGS,
    ACCOUNTS, DEVICE_CARE, SCREENSAVER, CONFIG, REMOTE, PROFILES, ABOUT, DNS,
    SPORTS, ADDONS, ADVANCED, ASSISTANT,
}

/** Where Back goes from a page: the low-level pages live under Advanced, everything else under the root list. */
private fun parentOf(page: SettingsPage): SettingsPage = when (page) {
    SettingsPage.REMOTE, SettingsPage.DNS, SettingsPage.CONFIG, SettingsPage.DEVICE_CARE -> SettingsPage.ADVANCED
    else -> SettingsPage.ROOT
}

/** The page's name, for the masthead and the Back hint. */
private fun pageTitle(page: SettingsPage): String = when (page) {
    SettingsPage.ROOT -> "Settings"
    SettingsPage.PLAYBACK -> "Playback"
    SettingsPage.PROVIDER -> "TV provider"
    SettingsPage.LAUNCHER -> "Theme & home"
    SettingsPage.YOUTUBE -> "YouTube"
    SettingsPage.RECORDINGS -> "Recordings"
    SettingsPage.ACCOUNTS -> "Accounts"
    SettingsPage.DEVICE_CARE -> "Diagnostics"
    SettingsPage.SCREENSAVER -> "Standby display"
    SettingsPage.CONFIG -> "Backup & restore"
    SettingsPage.REMOTE -> "Remote keys"
    SettingsPage.PROFILES -> "Profiles"
    SettingsPage.ABOUT -> "About"
    SettingsPage.DNS -> "DNS blocking"
    SettingsPage.SPORTS -> "Sports"
    SettingsPage.ADDONS -> "Add-ons"
    SettingsPage.ADVANCED -> "Advanced"
    SettingsPage.ASSISTANT -> "Assistant"
}

/** One-shot request for the next Settings screen to open straight on a page. */
internal object SettingsDeepLink {
    var addons = false
    var recordings = false
}

private const val ROOT_ROWS = 13   // keep in sync with rootEntries()

@Composable
fun SettingsScreen() {
    var page by rememberSaveable {
        mutableStateOf(
            when {
                SettingsDeepLink.addons -> SettingsPage.ADDONS
                SettingsDeepLink.recordings -> SettingsPage.RECORDINGS
                else -> SettingsPage.ROOT
            },
        ).also {
            SettingsDeepLink.addons = false
            SettingsDeepLink.recordings = false
        }
    }
    var rootSel by rememberSaveable { mutableIntStateOf(0) }
    // The row the user last opened. Unlike rootSel it is only set by opening a page, so an incidental focus event on
    // the list when it returns cannot overwrite where Back should land.
    var openedRow by rememberSaveable { mutableIntStateOf(0) }
    val rootFocusers = remember { List(ROOT_ROWS) { FocusRequester() } }
    val pageFocus = remember { FocusRequester() }

    // dialog state (Shield overlays these on top of the column)
    var showTrakt by rememberSaveable { mutableStateOf(false) }
    var showDebrid by rememberSaveable { mutableStateOf(false) }
    val cfg = remember { ConfigState() }

    // Settings-door PIN: memory latch dies with the process.
    // Zone "settings" opens once per app run; SET flow reuses the same overlay.
    var pinPrompt by remember { mutableStateOf<String?>(null) }   // null | "enter" | "set"
    val doorArmed = AppPreferences.settingsPinOn && AppPreferences.settingsPinIsSet()
    val doorOpen = doorArmed && PinLatch.isOpen("settings")

    /* BACK law, Shield-exact: dialog → page → root. Later handlers win. */
    BackHandler(enabled = page != SettingsPage.ROOT) { page = parentOf(page) }
    BackHandler(enabled = showTrakt) { showTrakt = false }
    BackHandler(enabled = showDebrid) { showDebrid = false }
    BackHandler(enabled = pinPrompt != null) { pinPrompt = null }

    // initial focus lands on the remembered row; returning from a page
    // restores it instead of snapping back to the top (Shield behaviour)
    // Returning to the list is handled in SettingsColumn, which scrolls the remembered row into view before
    // asking for its focus; a second request from here only raced with it.
    LaunchedEffect(page) {
        if (page != SettingsPage.ROOT) suspendRunCatching { pageFocus.awaitFocus() }
    }

    // Door PIN overlay: check BEFORE mounting SettingsColumn so focus never leaks underneath.
    if (doorArmed && !doorOpen && pinPrompt == null) {
        PinGate(
            title = "Settings",
            onOk = { pin ->
                if (AppPreferences.settingsPinVerify(pin)) { PinLatch.open("settings"); true } else false
            },
            onCancel = { pinPrompt = null; com.carteltv.tv.nav.Nav.pop() },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        // The root is the category list itself; detail pages keep their title and Back context.
        if (page != SettingsPage.ROOT) {
            ScreenMasthead(pageTitle(page), com.carteltv.tv.R.drawable.ph_gear_six)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SettingsColumn(page, rootSel, openedRow, rootFocusers, pageFocus, cfg,
                onRootSelect = { i -> rootSel = i },
                onOpen = { p -> openedRow = rootSel; page = p },
                onTrakt = { showTrakt = true },
                onDebrid = { showDebrid = true },
            )
        }
    }

    val cfgCtx = androidx.compose.ui.platform.LocalContext.current
    cfg.code?.let { code ->
        SettingsCodeDialog(heading = "Configuration code", initial = code, readOnly = true, primary = "Copy") {
            cfg.code = null
            if (it.isBlank()) return@SettingsCodeDialog
            val cm = cfgCtx.getSystemService(android.content.ClipboardManager::class.java)
            runCatching { cm?.setPrimaryClip(android.content.ClipData.newPlainText("cartel", code)) }
            cfg.status = "Copied to the clipboard."
        }
    }
    if (cfg.import) {
        SettingsCodeDialog(heading = "Import configuration code", initial = "", readOnly = false, primary = "Apply") { pasted ->
            cfg.import = false
            if (pasted.isNotBlank()) {
                runCatching {
                    val snap = com.carteltv.tv.data.setup.SetupCodec.decode(pasted)
                    cfg.snapshot = com.carteltv.tv.data.setup.SetupCodec.apply(snap)
                    cfg.canUndo = true
                    cfg.status = "Imported. You can revert to the previous configuration."
                }.onFailure { cfg.status = "The code is not valid: ${it.message ?: "it is malformed"}" }
            }
        }
    }

    DisposableEffect(Unit) {
        com.carteltv.tv.ui.settings.PinRequests.requestSet = { pinPrompt = "set" }
        onDispose { com.carteltv.tv.ui.settings.PinRequests.requestSet = null }
    }
    if (pinPrompt == "set") {
        PinGate(
            title = "Choose a new PIN",
            onOk = { pin ->
                if (pin.length in 1..8) {
                    AppPreferences.settingsPinSet(pin)
                    AppPreferences.settingsPinOn = true
                    PinLatch.open("settings")
                    pinPrompt = null
                    true
                } else false
            },
            onCancel = { pinPrompt = null },
        )
    }

    com.carteltv.tv.ui.settings.ProfilePinSetGate()

    if (showDebrid) {
        BackHandler(enabled = true) { showDebrid = false }
        Box(
            Modifier.fillMaxSize().background(ThemeSemantics.scrimModal).dismissScrim { showDebrid = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // Dialog chrome: the surface3 sheet, so the downloads' panel-toned cards at rest still read as cards on it.
                Modifier.trapFocus().fillMaxWidth(0.92f).fillMaxHeight(0.9f).clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                    .background(ThemeSemantics.surfaceModal)
                    .glassLight()
                    .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg)),
            ) { DebridDownloads() }
        }
    }

    if (showTrakt) {
        BackHandler(enabled = true) { showTrakt = false }
        Box(
            Modifier.fillMaxSize().background(ThemeSemantics.scrimModal).dismissScrim { showTrakt = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.trapFocus().fillMaxWidth(0.85f).clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                    .background(ThemeSemantics.surfaceModal)
                    .glassLight()
                    .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg)),
            ) { TraktPairPanel(onDone = { showTrakt = false }) }
        }
    }
}

@Composable
private fun SettingsColumn(
    page: SettingsPage,
    rootSel: Int,
    restoreSel: Int,
    rootFocusers: List<FocusRequester>,
    pageFocus: FocusRequester,
    cfg: ConfigState,
    onRootSelect: (Int) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onTrakt: () -> Unit,
    onDebrid: () -> Unit,
) {
    val scroll = rememberLazyListState()
    val rootGrid = rememberLazyGridState()
    val remap = remember { RemapUiState() }
    // Back from a page: bring the remembered row back into the list before asking for its focus, because a
    // row that has scrolled out of a lazy list has no focus target and the request would fail without a sound.
    LaunchedEffect(page) {
        if (page == SettingsPage.ROOT) {
            val row = restoreSel.coerceIn(0, ROOT_ROWS - 1)
            onRootSelect(row)
            rootGrid.scrollToItem((rootListIndex(row) - 2).coerceAtLeast(0))
            rootFocusers[row].awaitFocus()
        } else {
            // The list state is shared with the root list, so a page would open scrolled to wherever the root was,
            // with its first row, and its focus target, out of the list.
            scroll.scrollToItem(0)
        }
    }
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
    if (page == SettingsPage.ROOT) {
        // Categories are cards in a two-column grid that owns the full width; each card carries its own description,
        // so there is no separate detail pane.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = rootGrid,
            modifier = Modifier.fillMaxSize().focusGroup().restoreRowFocus(),
            contentPadding = com.carteltv.tv.ui.nav.PagePadding,
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
        ) {
            rootRows(this, rootFocusers, rootSel, onRootSelect, onOpen)
        }
    } else
    // Pages are flowing forms (sections, captions, rows in one item), so they stay a single wide column.
    LazyColumn(
        state = scroll,
        modifier = Modifier.fillMaxHeight().weight(1f).widthIn(max = 860.dp).focusGroup(),
        contentPadding = com.carteltv.tv.ui.nav.PagePadding,
    ) {
        when (page) {
            SettingsPage.ROOT -> Unit
            SettingsPage.PLAYBACK -> playbackPage(this, pageFocus)
            SettingsPage.PROVIDER -> providerPage(this, pageFocus)
            SettingsPage.LAUNCHER -> launcherPage(this, pageFocus)
            SettingsPage.YOUTUBE -> youtubePage(this, pageFocus)
            SettingsPage.RECORDINGS -> recordingsPage(this, pageFocus)
            SettingsPage.ACCOUNTS -> accountsPage(this, pageFocus, onTrakt, onDebrid)
            SettingsPage.DEVICE_CARE -> deviceCarePage(this, pageFocus)
            SettingsPage.SCREENSAVER -> screensaverPage(this, pageFocus)
            SettingsPage.CONFIG -> configPage(this, pageFocus, cfg)
            SettingsPage.REMOTE -> remotePage(this, pageFocus, remap)
            SettingsPage.PROFILES -> profilesPage(this, pageFocus)
            SettingsPage.ABOUT -> aboutPage(this, pageFocus)
            SettingsPage.ASSISTANT -> assistantPage(this, pageFocus)
            SettingsPage.DNS -> dnsPage(this, pageFocus)
            SettingsPage.SPORTS -> sportsPage(this, pageFocus)
            SettingsPage.ADDONS -> addonsPage(this, pageFocus)
            SettingsPage.ADVANCED -> advancedPage(this, pageFocus, onOpen)
        }
    }
    }
    }

    // remap capture overlay rides above everything
    val captureCtx = androidx.compose.ui.platform.LocalContext.current
    remap.capturePending?.let { flavor ->
        KeyCaptureOverlay(
            onCaptured = { fl, code -> remap.onCaptured(captureCtx, fl, code) },
            onCancel = { remap.capturePending = null },
        )
    }
}

/** REMOTE-page state (mirrors ConfigState pattern). */
internal class RemapUiState {
    /** flavor awaiting a key; null = idle */
    var capturePending by mutableStateOf<com.carteltv.tv.data.remap.KeyRemap.Flavor?>(null)
    /** action id awaiting a key capture */
    var actionForCapture by mutableStateOf<String?>(null)

    fun onCaptured(ctx: android.content.Context, flavor: com.carteltv.tv.data.remap.KeyRemap.Flavor, code: Int) {
        val act = actionForCapture
        capturePending = null
        actionForCapture = null
        if (act != null && code !in com.carteltv.tv.data.remap.KeyRemap.RESERVED) {
            AppPreferences.addRemapBinding(
                com.carteltv.tv.data.remap.KeyRemap.Bind(flavor, code, act),
            )
            com.carteltv.tv.data.remap.KeyRemapService.notifyPrefsChanged(ctx)
        }
    }
}

/** Root page: Shield opens with the bare list — categories only, no title. */
private fun rootRows(
    scope: LazyGridScope,
    focusers: List<FocusRequester>,
    sel: Int,
    onSelect: (Int) -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    var n = 0
    rootEntries().forEach { (heading, rows) ->
        scope.item(key = "h_$heading", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") { Section(heading) }
        val base = n
        n += rows.size
        scope.items(rows.size, key = { "c_${base + it}" }, contentType = { "card" }) { j ->
            val i = base + j
            val r = rows[j]
            CatRow(
                r.ico, androidx.compose.ui.res.stringResource(r.title),
                r.detail,
                modifier = Modifier.focusRequester(focusers[i]),
                status = r.status,
                focused = sel == i,
                onFocusGained = { onSelect(i) },
            ) { onSelect(i); onOpen(r.page) }
        }
    }
}

/**
 * Position in the root grid of category [sel]. Grid item indices count items in order regardless of column count,
 * and each group adds one full-span heading item before its cards, so the mapping is column-independent.
 */
private fun rootListIndex(sel: Int): Int {
    var rows = 0
    var headings = 0
    for ((_, group) in rootEntries()) {
        headings++
        if (sel < rows + group.size) return headings + sel
        rows += group.size
    }
    return headings + rows - 1
}

/** One settings category: its row in the list and the text the detail pane shows while it has focus. */
private class RootEntry(val ico: SettingsIcon, @androidx.annotation.StringRes val title: Int, val status: String, val detail: String, val page: SettingsPage)

/** The categories in list order. The list and the detail pane both read this, so they cannot drift apart. */
private fun rootEntries(): List<Pair<String, List<RootEntry>>> {
    val connected = AppPreferences.credentials != null
    return listOf(
        "Account" to listOf(
            RootEntry(SettingsIcon.PERSON, com.carteltv.tv.R.string.set_provider, if (connected) "Connected" else "Not connected",
                "Your server address, username and password, and the content languages to show.", SettingsPage.PROVIDER),
            RootEntry(SettingsIcon.NETWORK, com.carteltv.tv.R.string.set_linked_accounts, "",
                "Trakt, Simkl, Stremio Cloud, your TMDB key and the TV guide source.", SettingsPage.ACCOUNTS),
            RootEntry(SettingsIcon.PEOPLE, com.carteltv.tv.R.string.set_profiles, "",
                "Who is watching, which profile opens at startup, and profile rules.", SettingsPage.PROFILES),
        ),
        "Watching" to listOf(
            RootEntry(SettingsIcon.PLAYBACK, com.carteltv.tv.R.string.set_playback, "",
                "Display and audio, languages, up next, catch-up and multiview.", SettingsPage.PLAYBACK),
            RootEntry(SettingsIcon.INFO, com.carteltv.tv.R.string.set_sports, "",
                "Display, privacy, auto-play, time zone, channel matching and goal alerts.", SettingsPage.SPORTS),
            RootEntry(SettingsIcon.TUBE, com.carteltv.tv.R.string.set_youtube, "",
                "Direct feed, playback, segment bypass and feed filters.", SettingsPage.YOUTUBE),
            RootEntry(SettingsIcon.REC, com.carteltv.tv.R.string.set_recordings, "",
                "Saved and scheduled recordings, and how much extra time to add.", SettingsPage.RECORDINGS),
            RootEntry(SettingsIcon.GRID, com.carteltv.tv.R.string.set_addons, "",
                "Add and manage your add-ons.", SettingsPage.ADDONS),
        ),
        "Appearance" to listOf(
            RootEntry(SettingsIcon.GRID, com.carteltv.tv.R.string.set_theme, "",
                "Color themes, glass transparency, security, app channels, icon pack, sound and time format.", SettingsPage.LAUNCHER),
            RootEntry(SettingsIcon.MOON, com.carteltv.tv.R.string.set_standby, "",
                "What the TV shows while it is idle.", SettingsPage.SCREENSAVER),
        ),
        "System" to listOf(
            RootEntry(SettingsIcon.MIC, com.carteltv.tv.R.string.set_assistant, "",
                "Open Cartel with the remote mic button for apps, channels, playback and chained commands.", SettingsPage.ASSISTANT),
            RootEntry(SettingsIcon.REMOTE, com.carteltv.tv.R.string.set_advanced, "",
                "Buffering, display, audio, controls, DNS blocking, backup and diagnostics.", SettingsPage.ADVANCED),
            RootEntry(SettingsIcon.MOON, com.carteltv.tv.R.string.set_about, "",
                "System information and updates.", SettingsPage.ABOUT),
        ),
    )
}

@Composable
internal fun SettingsCodeDialog(
    heading: String,
    initial: String,
    readOnly: Boolean,
    primary: String,
    onClear: (() -> Unit)? = null,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val primaryFocus = remember { FocusRequester() }
    // The export box takes no input, but if focus lands on it, it should answer like any field.
    var codeFocused by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(ThemeSpec.Card.corner)
    LaunchedEffect(Unit) { suspendRunCatching { primaryFocus.awaitFocus() } }
    BackHandler(enabled = true) { onDone("") }
    Box(
        Modifier.fillMaxSize().trapFocus().background(ThemeSemantics.scrimModal)
            .clickableTv(hoverEffect = false, showHand = false) { onDone("") },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .background(ThemeSemantics.surfaceModal)
                .glassLight()
                .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .padding(ThemeSpec.Space.lg),
        ) {
            Text(heading, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.accent, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            // Multi-line and, when exporting, read-only: not a TvTextField, but the same surface steps (surface2 to surface4 on focus).
            BasicTextField(
                value = text,
                onValueChange = { if (!readOnly) text = it },
                readOnly = readOnly,
                textStyle = TextStyle(
                    fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textEmphatic,
                    fontFamily = ThemeSpec.Font.mono,
                ),
                cursorBrush = SolidColor(ThemeSemantics.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp)
                    .then(if (!readOnly) Modifier.focusRequester(primaryFocus) else Modifier)
                    .onFocusChanged { codeFocused = it.isFocused }
                    .clip(fieldShape)
                    .background(if (codeFocused) ThemeSemantics.surfaceFocus else ThemeSemantics.surfaceRaised)
                    .focusRing(codeFocused, fieldShape, idle = ThemeSemantics.hairline)
                    .glassLight()
                    .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
            )
            Spacer(Modifier.height(ThemeSpec.Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                ActionButton(
                    primary,
                    filled = true,
                    modifier = if (readOnly) Modifier.focusRequester(primaryFocus) else Modifier,
                ) { onDone(text) }
                ActionButton("Close", filled = false) { onDone("") }
                if (onClear != null) ActionButton("Clear", filled = false) { onClear() }
            }
            if (readOnly) {
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                Text("Credentials are excluded and remain local.",
                    fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
