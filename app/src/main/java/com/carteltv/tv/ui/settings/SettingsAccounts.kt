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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.graphics.Brush
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
import com.carteltv.tv.data.network.isHttpUrl
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.components.TvTextField
import com.carteltv.tv.ui.components.OverlayCoordinator
import com.carteltv.tv.data.events.Holidays
import com.carteltv.tv.ui.home.NowOn
import com.carteltv.tv.ui.nav.ScreenMasthead
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.components.glassRowDim
import com.carteltv.tv.ui.components.glassRowText
internal fun accountsPage(scope: LazyListScope, focus: FocusRequester, onTrakt: () -> Unit, onDebrid: () -> Unit) {
    pageHead(scope, "Accounts")
    scope.item {
        var tmdbOpen by remember { mutableStateOf(false) }
        var tmdbTick by remember { mutableIntStateOf(0) }
        val hasTmdb = remember(tmdbTick) { AppPreferences.tmdbKey.isNotBlank() }
        ValueRow(
            title = if (TraktAuth.client != null) "Trakt · ${if (TraktAuth.needsReconnect) "Reconnect needed" else TraktAuth.userName ?: "Linked"}" else "Trakt",
            desc = "Device authorization and history sync",
            value = if (TraktAuth.client != null) "Manage" else "Link", focus = focus,
        ) { onTrakt() }
        ValueRow(
            title = if (hasTmdb) "TMDB · Configured" else "TMDB key",
            desc = "themoviedb.org. Provides metadata and indexing",
            value = if (hasTmdb) "Edit" else "Add", focus = null,
        ) { tmdbOpen = !tmdbOpen }
        if (tmdbOpen) {
            var key by remember { mutableStateOf(TextFieldValue(AppPreferences.tmdbKey, TextRange(AppPreferences.tmdbKey.length))) }
            Section("TMDB API key")
            TvTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier
                    .padding(start = ThemeSpec.Space.md, bottom = ThemeSpec.Space.sm)
                    .widthIn(max = 420.dp),
            )
            Row(Modifier.padding(start = ThemeSpec.Space.md, bottom = ThemeSpec.Space.sm), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                ActionButton("Save", filled = key.text.isNotBlank()) { AppPreferences.tmdbKey = key.text; tmdbTick++; tmdbOpen = false }
                if (hasTmdb) {
                    ActionButton("Clear", filled = false) { AppPreferences.tmdbKey = ""; key = TextFieldValue(""); tmdbTick++ }
                }
            }
        }
        ValueRow(
            title = if (AppPreferences.debridToken.isNotBlank()) "Debrid · Linked" else "Debrid",
            desc = "Resolves sources and stores downloads",
            value = if (AppPreferences.debridToken.isNotBlank()) "Manage" else "Link", focus = null,
        ) { onDebrid() }

        val accScope = rememberCoroutineScope()
        val epgCtx = androidx.compose.ui.platform.LocalContext.current
        var epgTick by remember { mutableIntStateOf(0) }
        var epgBusy by remember { mutableStateOf(false) }
        Section("TV guide (EPG)")
        val epgStatus = remember(epgTick, epgBusy) {
            val ok = AppPreferences.epgSyncOkMs
            when {
                epgBusy -> "Updating…"
                ok == 0L -> "Not downloaded yet"
                else -> {
                    val mins = ((System.currentTimeMillis() - ok) / 60_000L).coerceAtLeast(0)
                    "Updated " + if (mins < 60) "$mins min ago" else "${mins / 60} h ago"
                }
            }
        }
        ValueRow(
            title = "Update guide now", desc = epgStatus,
            value = if (epgBusy) "…" else "Update", focus = null,
        ) {
            val client = ProviderSession.current
            if (epgBusy) return@ValueRow
            if (client == null) {
                TransientStatus.show(NEED_PROVIDER)
                return@ValueRow
            }
            epgBusy = true
            accScope.launch {
                val ok = suspendRunCatching { com.carteltv.tv.data.xtream.EpgSync.refreshIfDue(epgCtx, client, force = true) }
                    .getOrDefault(false)
                epgBusy = false
                epgTick++
                TransientStatus.show(if (ok) "Guide updated." else "The guide could not be updated. Check the guide URL.")
            }
        }
        val customUrl = remember(epgTick) { AppPreferences.customXmltvUrl }
        ValueRow(
            title = "Custom guide URL", desc = customUrl.ifBlank { "Using your provider's guide" },
            value = if (customUrl.isNotBlank()) "Edit" else "Add", focus = null,
        ) {
            TvDialogs.input(
                "Custom XMLTV URL", initial = customUrl, hint = "https://…/guide.xml",
                extraLabel = "Use provider's guide",
                onExtra = { AppPreferences.customXmltvUrl = ""; AppPreferences.epgSyncOkMs = 0L; epgTick++ },
            ) { value ->
                val v = value.trim()
                val valid = v.isEmpty() || v.isHttpUrl()
                if (valid) { AppPreferences.customXmltvUrl = v; AppPreferences.epgSyncOkMs = 0L; epgTick++ }
                else TransientStatus.show(NEED_HTTP_URL)
            }
        }
        ValueRow(
            title = SUGGESTED_GUIDES, desc = "Free guides by country from EPG Share",
            value = "Pick", focus = null,
        ) {
            val presets = com.carteltv.tv.data.xtream.EpgPresets.all
            TvDialogs.menu(SUGGESTED_GUIDES, presets.map { it.label }) { i ->
                AppPreferences.customXmltvUrl = presets[i].url; AppPreferences.epgSyncOkMs = 0L; epgTick++
            }
        }
        val extraUrls = remember(epgTick) { AppPreferences.extraGuideUrls }
        Caption("Extra guides supply channel listings that your provider's guide does not have. They are read in this order.")
        extraUrls.forEachIndexed { i, u ->
            ValueRow(
                title = "${i + 1}. ${guideLabel(u)}", desc = u,
                value = "Manage", focus = null,
            ) { showGuideMenu(epgCtx, accScope, u) { epgTick++ } }
        }
        ValueRow(
            title = "Add guide",
            desc = "${extraUrls.size} of ${com.carteltv.tv.data.xtream.EpgSync.MAX_SOURCES - 1} extra guides",
            value = "Add", focus = null,
        ) { showAddGuide(epgCtx, accScope) { epgTick++ } }
        val idExport = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            accScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    runCatching {
                        val ids = com.carteltv.tv.data.xtream.EpgIndexDatabase.get(epgCtx).providerEpgIds()
                        if (ids.isEmpty()) "No channel IDs yet. Open Live TV once, then try again."
                        else {
                            val text = com.carteltv.tv.data.xtream.EpgIdExport.format(ids)
                            (epgCtx.contentResolver.openOutputStream(uri, "wt")
                                ?: throw IllegalStateException("Could not open output stream"))
                                .use { it.write(text.toByteArray(Charsets.UTF_8)) }
                            "Saved ${com.carteltv.tv.data.xtream.EpgIdExport.count(ids)} channel IDs."
                        }
                    }.getOrElse { "Export failed: ${it.message}" }
                }
                TransientStatus.show(msg)
            }
        }
        ValueRow(
            title = "Export channel IDs", desc = "Your channels' guide IDs in a text file, without login details",
            value = "Export", focus = null,
        ) {
            runCatching { idExport.launch("provider_epg_ids.txt") }
                .onFailure { TransientStatus.show("No file picker available on this device.") }
        }
        Caption("A custom URL replaces your provider's guide. Channels are matched by their guide ID.")
        Section("Simkl")
        var simklTick by remember { mutableIntStateOf(0) }
        var simklMsg by remember { mutableStateOf("") }
        var simklPin by remember { mutableStateOf<com.carteltv.tv.data.simkl.SimklClient.PinCode?>(null) }
        val simklConnected = remember(simklTick) { com.carteltv.tv.data.simkl.SimklClient.isConnected }
        val simklHasId = remember(simklTick) { com.carteltv.tv.data.simkl.SimklClient.isConfigured }
        var simklIdEdit by remember { mutableStateOf(false) }
        ValueRow(
            title = if (simklConnected) "Simkl · ${com.carteltv.tv.data.simkl.SimklClient.userName.ifBlank { "Linked" }}" else "Simkl",
            desc = if (simklHasId) "Device pairing. Syncs Continue Watching and Plan to Watch"
                   else "Enter your Simkl client ID, or send it from the phone companion",
            value = if (simklConnected) "Disconnect" else if (simklHasId) "Link" else "Client ID",
            focus = null,
        ) {
            when {
                simklConnected -> {
                    com.carteltv.tv.data.simkl.SimklClient.signOut()
                    simklTick++; simklMsg = "Signed out of Simkl."
                    simklPin = null
                }
                !simklHasId -> simklIdEdit = !simklIdEdit
                else -> accScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val pin = com.carteltv.tv.data.simkl.SimklClient.startPairing()
                        simklPin = pin
                        val ok = com.carteltv.tv.data.simkl.SimklClient.pollPairing(pin)
                        simklMsg = if (ok) "Simkl connected." else com.carteltv.tv.data.simkl.SimklClient.lastError
                        simklPin = null
                        simklTick++
                    }.onFailure {
                        simklMsg = it.message ?: "Simkl could not be connected"
                        simklPin = null
                    }
                }
            }
        }
        if (simklHasId && !simklConnected) {
            ValueRow(
                title = "Simkl client ID", desc = "Edit or remove the saved client ID",
                value = "Edit", focus = null,
            ) { simklIdEdit = !simklIdEdit }
        }
        if (simklIdEdit) {
            var cid by remember { mutableStateOf(AppPreferences.simklClientId) }
            CompactField(
                value = cid,
                placeholder = "Simkl client ID",
                modifier = Modifier.padding(start = ThemeSpec.Space.md, bottom = ThemeSpec.Space.sm).widthIn(max = 420.dp),
            ) { cid = it }
            Row(Modifier.padding(start = ThemeSpec.Space.md, bottom = ThemeSpec.Space.sm), horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                ActionButton("Save", filled = cid.isNotBlank()) {
                    AppPreferences.simklClientId = cid; simklTick++; simklIdEdit = false
                }
                if (simklHasId) {
                    ActionButton("Clear", filled = false) {
                        AppPreferences.simklClientId = ""; cid = ""; simklTick++; simklIdEdit = false
                    }
                }
            }
        }
        simklPin?.let { pin ->
            Column(
                Modifier.padding(start = ThemeSpec.Space.md, top = ThemeSpec.Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QrCode(text = pin.qrUrl, cellSize = 6.dp)
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                Text(
                    pin.userCode,
                    fontSize = ThemeSpec.Type.titleL,
                    fontWeight = FontWeight.Black,
                    color = ThemeSemantics.accentBright,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(ThemeSpec.Space.xs))
                Text("Go to ${pin.verifyUrl}", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (simklMsg.isNotBlank() && simklPin == null) Result(simklMsg)

        Section("Stremio Cloud")
        var stremioTick by remember { mutableIntStateOf(0) }
        var stremioMsg by remember { mutableStateOf("") }
        var stremioSession by remember { mutableStateOf<com.carteltv.tv.data.stremio.StremioAccount.LinkSession?>(null) }
        val stremioConnected = remember(stremioTick) { com.carteltv.tv.data.stremio.StremioAccount.isConnected }
        ValueRow(
            title = if (stremioConnected) "Stremio · ${com.carteltv.tv.data.stremio.StremioAccount.email.ifBlank { "Linked" }}" else "Stremio account",
            desc = "Link with a code from your phone. Syncs cloud add-ons and watch history",
            value = if (stremioConnected) "Sync add-ons" else "Link",
            focus = null,
        ) {
            if (stremioConnected) {
                accScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val res = com.carteltv.tv.data.stremio.StremioAccount.syncAddonsToPreferences()
                    stremioMsg = res.fold(
                        onSuccess = { n -> "Synced $n new Stremio add-on(s)." },
                        onFailure = { e -> "Sync failed: ${e.message}" },
                    )
                }
            } else {
                accScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val sess = com.carteltv.tv.data.stremio.StremioAccount.startLink()
                        stremioSession = sess
                        val ok = com.carteltv.tv.data.stremio.StremioAccount.pollLink(sess)
                        stremioMsg = if (ok) {
                            // Import cloud addons right away so On Demand rows populate without a second tap.
                            val synced = com.carteltv.tv.data.stremio.StremioAccount.syncAddonsToPreferences()
                            synced.fold(
                                onSuccess = { n -> "Stremio linked. Imported $n add-on(s)." },
                                onFailure = { e -> "Stremio linked, but add-ons could not be synced: ${e.message}" },
                            )
                        } else com.carteltv.tv.data.stremio.StremioAccount.lastError
                        stremioSession = null
                        stremioTick++
                    }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        stremioMsg = it.message ?: "Stremio could not be linked"
                        stremioSession = null
                    }
                }
            }
        }
        stremioSession?.let { sess ->
            Column(
                Modifier.padding(start = ThemeSpec.Space.md, top = ThemeSpec.Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QrCode(text = sess.verifyUrl, cellSize = 6.dp)
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                Text(
                    sess.code,
                    fontSize = ThemeSpec.Type.titleL,
                    fontWeight = FontWeight.Black,
                    color = ThemeSemantics.accentBright,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(ThemeSpec.Space.xs))
                Text("Go to ${sess.verifyUrl}", fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (stremioConnected) {
            ValueRow(
                title = "Unlink Stremio", desc = "Removes the saved Stremio sign-in",
                value = "Disconnect", focus = null,
            ) {
                com.carteltv.tv.data.stremio.StremioAccount.signOut()
                stremioTick++; stremioMsg = "Signed out of Stremio."
            }
        }
        if (stremioMsg.isNotBlank()) Result(stremioMsg)
    }
}

internal fun deviceCarePage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Diagnostics", "Advanced")
    scope.item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var assessing by remember { mutableStateOf(false) }
        var notes by remember { mutableStateOf(AppPreferences.assessmentNotes) }
        val scope2 = rememberCoroutineScope()
        val assessed = notes.isNotBlank()
        ValueRow(
            title = if (assessed) "Device check · Complete" else "Device check",
            desc = notes.ifBlank { "Memory · Video · Audio · Storage" },
            value = if (assessing) "Running…" else if (assessed) "Run again" else "Run", focus = focus,
        ) {
            if (assessing) return@ValueRow
            assessing = true
            scope2.launch {
                val v = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.carteltv.tv.engine.stability.DeviceBudget.probe(ctx)
                }
                AppPreferences.assessmentNotes = v.summaryAndNotes
                AppPreferences.bufferPreset = v.recommendedPreset
                notes = v.summaryAndNotes
                assessing = false
            }
        }
        if (notes.isNotBlank()) Result(notes)

        val hasPerm = remember { com.carteltv.tv.engine.stability.HomeAuthority.canWriteSecure(ctx) }
        val homeState = remember { com.carteltv.tv.engine.stability.HomeAuthority.homeSummary(ctx) }
        var resultLine by remember { mutableStateOf<String?>(null) }
        ValueRow(
            title = if (hasPerm) "System permission · Granted" else "System permission",
            desc = homeState + if (hasPerm) "" else " · Grant WRITE_SECURE_SETTINGS via ADB",
            value = if (hasPerm) "Apply" else "Info", focus = null,
        ) {
            if (!hasPerm) {
                resultLine = "adb shell pm grant com.carteltv.tv android.permission.WRITE_SECURE_SETTINGS"
            } else {
                resultLine = com.carteltv.tv.engine.stability.HomeAuthority.registerScreensaver(ctx)
                if (!com.carteltv.tv.engine.stability.HomeAuthority.listenerGranted(ctx)) {
                    resultLine += " · " + com.carteltv.tv.engine.stability.HomeAuthority.grantRevivalSelf(ctx)
                }
            }
        }
        resultLine?.let { Result(it) }

        val installed = remember { runCatching { ctx.packageManager.getPackageInfo("com.shielddoctor.tv", 0) }.isSuccess }
        ValueRow(
            title = "Shield Doctor",
            desc = if (installed) "Storage · Background processes"
                   else "Not installed",
            value = if (installed) "Open" else "Unavailable", focus = null,
        ) {
            if (installed) runCatching {
                ctx.startActivity(
                    Intent().setClassName("com.shielddoctor.tv", "com.shielddoctor.tv.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        Section("Timers")
        var timerLine by remember { mutableStateOf<String?>(null) }
        var sleepDefault by remember { mutableIntStateOf(AppPreferences.sleepMinutes) }
        Caption("Standby timer: shows the idle display when the time is up")
        listOf(15, 30, 45, 60, 90).forEach { m ->
            ValueRow(
                title = "Standby · $m min",
                desc = "Shows the standby clock when the time is up",
                value = if (sleepDefault == m) "Default" else "Start", focus = null,
            ) {
                AppPreferences.sleepMinutes = m
                sleepDefault = m
                SleepTimerState.armSleep(m)
                timerLine = "Standby timer set for " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(java.util.Date(SleepTimerState.sleepAtMs))
            }
        }
        Caption("Auto-pause: pauses playback when the time is up")
        listOf(30, 60, 120).forEach { m ->
            ValueRow(
                title = "Auto-pause · $m min",
                desc = "Applies to the next video you play",
                value = "Start", focus = null,
            ) {
                SleepTimerState.armMediaStop(m)
                timerLine = "Auto-pause timer set for $m min."
            }
        }
        ValueRow(
            title = "Clear auto-pause", desc = "Removes the auto-pause timer",
            value = "Clear", focus = null,
        ) { SleepTimerState.clearMedia(); timerLine = "Cleared." }
        timerLine?.let { Result(it) }

        Section("Notices")
        var noticePopups by remember { mutableStateOf(AppPreferences.noticePopups) }
        ValueRow(
            title = if (NotificationMirror.connected) "Listener active · ${NotificationMirror.items.size} queued"
                    else "Listener inactive",
            desc = if (NotificationMirror.connected) "The latest notices are listed below"
                   else "adb: cmd notification allow_listener com.carteltv.tv/.guard.NotificationListenerGuard",
            value = if (noticePopups) "On" else "Off", focus = null,
        ) {
            noticePopups = !noticePopups
            AppPreferences.noticePopups = noticePopups
        }
        NotificationMirror.items.take(4).forEach { n ->
            ValueRow(
                title = n.title.ifBlank { n.pkg },
                desc = n.text.take(90).ifBlank { " " },
                value = "Clear", focus = null,
            ) { runCatching { NotificationMirror.items.removeAll { it.key == n.key } } }
        }

        Section("Diagnostic bundle")
        var diagText by remember { mutableStateOf("") }
        ValueRow(
            title = "Privacy-safe report",
            desc = "Removes tokens, IP and MAC addresses, emails and RTSP links, then copies the result to the clipboard.",
            value = "Generate", focus = null,
        ) {
            scope2.launch {
                val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val snap = com.carteltv.tv.engine.stability.DiagnosticsReport.build(ctx)
                    com.carteltv.tv.engine.stability.DiagnosticsReport.format(snap)
                }
                diagText = txt
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                runCatching { cm?.setPrimaryClip(android.content.ClipData.newPlainText("carteltv_diag", txt)) }
            }
        }
        if (diagText.isNotBlank()) Result(diagText.take(420))
    }
}

internal fun screensaverPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Standby display")
    scope.item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        ValueRow(
            title = "CartelTV standby clock",
            desc = "Open the system screen saver settings",
            value = "Open", focus = focus,
        ) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

internal class ConfigState {
    var code by mutableStateOf<String?>(null)
    var import by mutableStateOf(false)
    var status by mutableStateOf("")
    var canUndo by mutableStateOf(false)
    var snapshot by mutableStateOf<String?>(null)
}

internal fun configPage(scope: LazyListScope, focus: FocusRequester, cfg: ConfigState) {
    pageHead(scope, "Backup & restore", "Advanced")
    scope.item {
        ValueRow(
            title = "Export configuration",
            desc = "Creates a portable code. Passwords and keys are not included.",
            value = "Export", focus = focus,
        ) {
            cfg.code = com.carteltv.tv.data.setup.SetupCodec.encode(
                com.carteltv.tv.data.setup.SetupCodec.capture())
            cfg.status = ""
        }
        ValueRow(
            title = "Import configuration",
            desc = "Applies immediately. You can revert to the previous configuration.",
            value = "Import", focus = null,
        ) { cfg.import = true }
        if (cfg.canUndo) {
            ValueRow(
                title = "Revert import",
                desc = "Restore the previous configuration",
                value = "Revert", focus = null,
            ) {
                runCatching {
                    val snap = cfg.snapshot ?: return@ValueRow
                    com.carteltv.tv.data.setup.SetupCodec.apply(
                        com.carteltv.tv.data.setup.SetupCodec.decode(snap))
                    cfg.canUndo = false
                    cfg.status = "The previous configuration has been restored."
                }
            }
        }
        val libCtx = androidx.compose.ui.platform.LocalContext.current
        val backupScope = rememberCoroutineScope()
        val libExport = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            backupScope.launch {
                cfg.status = "Saving library…"
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = com.carteltv.tv.data.setup.LibraryBackup.encode(
                            com.carteltv.tv.data.preferences.AppPreferences.favIds(), com.carteltv.tv.data.preferences.AppPreferences.recents())
                        (libCtx.contentResolver.openOutputStream(uri, "wt")
                            ?: throw IllegalStateException("Could not open output stream"))
                            .use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        "Library saved."
                    }.getOrElse { "Export failed: ${it.message}" }
                }
                cfg.status = result
            }
        }
        val libImport = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            backupScope.launch {
                cfg.status = "Restoring library…"
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = (libCtx.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Could not open input stream"))
                            .use { it.readBytes().toString(Charsets.UTF_8) }
                        val (favs, recents) = com.carteltv.tv.data.preferences.AppPreferences.restoreLibrary(
                            com.carteltv.tv.data.setup.LibraryBackup.decode(text))
                        "Library restored. Added $favs favourites and $recents continue-watching items."
                    }.getOrElse { "Import failed: ${it.message}" }
                }
                cfg.status = result
            }
        }
        ValueRow(
            title = "Export library",
            desc = "Save favourites and continue watching to a file",
            value = "Export", focus = null,
        ) {
            runCatching { libExport.launch("carteltv-library.json") }
                .onFailure { cfg.status = "No file picker available on this device." }
        }
        ValueRow(
            title = "Import library",
            desc = "Adds the contents of a saved file. Nothing is removed.",
            value = "Import", focus = null,
        ) {
            runCatching { libImport.launch(arrayOf("application/json", "text/plain", "*/*")) }
                .onFailure { cfg.status = "No file picker available on this device." }
        }
        cfg.status.takeIf { it.isNotBlank() }?.let { Result(it) }

        val cfgCtx2 = androidx.compose.ui.platform.LocalContext.current
        Section("Phone companion")
        var companionOn by remember { mutableStateOf(com.carteltv.tv.companion.PhoneCompanionServer.running) }
        var companionUrl by remember { mutableStateOf(com.carteltv.tv.companion.PhoneCompanionServer.activeUrl(cfgCtx2).orEmpty()) }
        DisposableEffect(Unit) {
            onDispose { com.carteltv.tv.companion.PhoneCompanionServer.stop() }
        }
        ToggleRow(
            title = "Control from phone",
            desc = if (companionOn && companionUrl.isNotBlank()) companionUrl
                   else "Starts a token-protected web page on your local network (ports 9090–9099)",
            value = companionOn, focus = null,
        ) {
            if (companionOn) {
                com.carteltv.tv.companion.PhoneCompanionServer.stop()
                companionOn = false
                companionUrl = ""
            } else {
                val p = com.carteltv.tv.companion.PhoneCompanionServer.start(cfgCtx2)
                companionOn = p != null
                companionUrl = com.carteltv.tv.companion.PhoneCompanionServer.activeUrl(cfgCtx2)
                    ?: if (p != null) "Listening on port $p (no IPv4)" else "No free port"
            }
        }
        if (companionOn && companionUrl.isNotBlank()) {
            Result("On your phone, open $companionUrl")
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            QrCode(text = companionUrl, cellSize = 5.dp)
        }

        Section("Sync between TVs")
        var peerMain by remember { mutableStateOf(AppPreferences.peerSyncRole == "main") }
        var pairCodeShown by remember { mutableStateOf(com.carteltv.tv.data.sync.PeerSync.pairCode) }
        ToggleRow(
            title = "Use as the main TV",
            desc = "Syncs selected settings with other TVs on your network, never passwords or keys",
            value = peerMain, focus = null,
        ) {
            peerMain = !peerMain
            if (peerMain) {
                AppPreferences.peerSyncRole = "main"
                com.carteltv.tv.data.sync.PeerSync.startMainServer(cfgCtx2)
            } else {
                AppPreferences.peerSyncRole = "off"
                com.carteltv.tv.data.sync.PeerSync.stopMainServer()
                pairCodeShown = ""
            }
        }
        if (peerMain) {
            ValueRow(
                title = "Show pairing code",
                desc = if (pairCodeShown.isNotBlank()) "Code: $pairCodeShown · Port ${com.carteltv.tv.data.sync.PeerSync.activePort}"
                       else "Creates a four-digit code that other TVs can use to pair",
                value = "Show code", focus = null,
            ) {
                pairCodeShown = com.carteltv.tv.data.sync.PeerSync.openPairing()
            }
            if (pairCodeShown.isNotBlank()) Result("Pairing code: $pairCodeShown (valid for 5 minutes)")
        }
    }
}

