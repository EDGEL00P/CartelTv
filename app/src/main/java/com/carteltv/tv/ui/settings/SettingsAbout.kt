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
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.components.glassRowDim
import com.carteltv.tv.ui.components.glassRowText
internal fun aboutPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "About")
    scope.item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val ver = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
                .getOrNull() ?: "0.7.0"
        }
        PlainRow(
            title = "CartelTV · Android TV",
            desc = "v$ver · Kotlin · Compose TV · Media3", focus = focus,
        )

        /* ── UPDATER (fed-by-URL law) ── */
        Section("Updates")
        var upState by remember { mutableStateOf(com.carteltv.tv.update.ReleaseUpdater.status) }
        var upVer by remember { mutableStateOf(com.carteltv.tv.update.ReleaseUpdater.latest?.versionName.orEmpty()) }
        val upScope = rememberCoroutineScope()
        ValueRow(
            title = "Check for updates",
            desc = upState.ifBlank { "Current version: $ver" },
            value = if (com.carteltv.tv.update.ReleaseUpdater.downloading) "…" else "Check",
            focus = null,
        ) {
            com.carteltv.tv.update.ReleaseUpdater.status = "Checking…"
            upState = "Checking…"
            upScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val r = com.carteltv.tv.update.ReleaseUpdater.check(ctx)
                if (r != null) {
                    upVer = r.versionName
                    upState = "Update available: ${r.versionName}"
                } else {
                    upState = com.carteltv.tv.update.ReleaseUpdater.status
                }
            }
        }
        if (upVer.isNotBlank()) {
            ValueRow(
                title = "Install ${upVer}",
                desc = "Download and install the update",
                value = "Install", focus = null,
            ) {
                com.carteltv.tv.update.ReleaseUpdater.latest?.let {
                    com.carteltv.tv.update.ReleaseUpdater.download(ctx, it)
                }
            }
        }
    }
}

internal fun dnsPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "DNS blocking", "Advanced")
    scope.item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val dns = remember(ctx) { com.carteltv.tv.data.network.PrivateDnsController(ctx) }
        val dnsScope = rememberCoroutineScope()
        PlainRow(
            title = "Status",
            desc = if (!dns.canControl) "Needs the WRITE_SECURE_SETTINGS permission (grant it via ADB)"
            else if (dns.isOn) "Blocking active" else "Off",
            focus = focus,
        )
        ChoiceRow(
            title = "AdGuard DNS",
            selected = dns.provider == com.carteltv.tv.data.network.PrivateDnsController.Provider.ADGUARD,
            focus = null,
            onClick = { dns.setProvider(com.carteltv.tv.data.network.PrivateDnsController.Provider.ADGUARD) },
        )
        ChoiceRow(
            title = "NextDNS",
            selected = dns.provider == com.carteltv.tv.data.network.PrivateDnsController.Provider.NEXTDNS,
            focus = null,
            onClick = { dns.setProvider(com.carteltv.tv.data.network.PrivateDnsController.Provider.NEXTDNS) },
        )
        // Writes require WRITE_SECURE_SETTINGS; unguarded taps would crash with SecurityException.
        ToggleRow(
            title = "Enable blocking",
            desc = if (dns.canControl) null else "Grant the WRITE_SECURE_SETTINGS permission first",
            value = dns.canControl && dns.isOn,
            focus = null,
            onClick = {
                if (!dns.canControl) return@ToggleRow
                if (dns.isOn) dns.turnOff() else {
                    dnsScope.launch { dns.turnOnSafely() }
                }
            },
        )
        PlainRow(
            title = "Pause for 30 minutes",
            desc = if (dns.canControl && dns.isOn) "Temporarily turns blocking off" else "Unavailable",
            focus = null,
            onClick = { if (dns.canControl && dns.isOn) dns.pause(30) },
        )
    }
}

internal fun sportsPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Sports")
    scope.item {
        var autoPlay by remember { mutableStateOf(AppPreferences.sportsAutoPlay) }
        var hideEmpty by remember { mutableStateOf(AppPreferences.sportsHideEmptyLeagues) }
        var preferQuality by remember { mutableStateOf(AppPreferences.sportsPreferQuality) }
        var favLeague by remember { mutableStateOf(AppPreferences.sportsFavoriteLeague) }
        var goalDelay by remember { mutableIntStateOf(AppPreferences.goalAlertDelay) }
        var countries by remember { mutableStateOf(AppPreferences.sportsPreferredCountries().joinToString(", ")) }

        Section("Display")
        ToggleRow(
            title = "Hide empty leagues", desc = "Show only leagues that have games",
            value = hideEmpty, focus = focus,
        ) { AppPreferences.sportsHideEmptyLeagues = !AppPreferences.sportsHideEmptyLeagues; hideEmpty = AppPreferences.sportsHideEmptyLeagues }
        ToggleRow(
            title = "Prefer quality", desc = "Choose stream quality over speed",
            value = preferQuality, focus = null,
        ) { AppPreferences.sportsPreferQuality = !AppPreferences.sportsPreferQuality; preferQuality = AppPreferences.sportsPreferQuality }

        Section("Privacy")
        var shielded by remember { mutableStateOf(AppPreferences.spoilerShield) }
        ToggleRow(
            title = "Spoiler shield", desc = "Blur scores and delay goal alerts",
            value = shielded, focus = null,
        ) { AppPreferences.spoilerShield = !AppPreferences.spoilerShield; shielded = AppPreferences.spoilerShield }

        Section("Auto-play")
        ToggleRow(
            title = "Auto-play reminders", desc = "Start a reminded game on its channel",
            value = autoPlay, focus = null,
        ) { AppPreferences.sportsAutoPlay = !AppPreferences.sportsAutoPlay; autoPlay = AppPreferences.sportsAutoPlay }

        Section("Time zone")
        val tzScope = androidx.compose.runtime.rememberCoroutineScope()
        var tzTick by remember { mutableIntStateOf(0) }
        val tzDesc = remember(tzTick) { com.carteltv.tv.data.espn.SportsTimeZone.describe() }
        ValueRow(
            title = "Sports time zone", desc = tzDesc,
            value = "Change", focus = null,
        ) {
            val modes = com.carteltv.tv.data.espn.SportsTimeZone.Mode.entries
            TvDialogs.menu("Sports time zone", modes.map { it.label }) { i ->
                when (modes[i]) {
                    com.carteltv.tv.data.espn.SportsTimeZone.Mode.AUTO -> {
                        AppPreferences.sportsZoneMode = modes[i].key
                        com.carteltv.tv.data.espn.SportsTimeZone.reload(); tzTick++
                        tzScope.launch {
                            suspendRunCatching { com.carteltv.tv.data.espn.SportsTimeZone.refreshIfStale(force = true) }
                            com.carteltv.tv.data.espn.SportsTimeZone.reload(); tzTick++
                        }
                    }
                    com.carteltv.tv.data.espn.SportsTimeZone.Mode.DEVICE -> {
                        AppPreferences.sportsZoneMode = modes[i].key
                        com.carteltv.tv.data.espn.SportsTimeZone.reload(); tzTick++
                    }
                    com.carteltv.tv.data.espn.SportsTimeZone.Mode.MANUAL -> {
                        val now = System.currentTimeMillis()
                        val regions = com.carteltv.tv.data.espn.TimeZoneCatalog.REGIONS
                        TvDialogs.menu("Region", regions) { r ->
                            val zones = com.carteltv.tv.data.espn.TimeZoneCatalog.zones(regions[r], now)
                            TvDialogs.menu(regions[r], zones.map { it.label }, cancelLabel = "Back") { z ->
                                AppPreferences.sportsZoneManual = zones[z].id
                                AppPreferences.sportsZoneMode = com.carteltv.tv.data.espn.SportsTimeZone.Mode.MANUAL.key
                                com.carteltv.tv.data.espn.SportsTimeZone.reload(); tzTick++
                            }
                        }
                    }
                }
            }
        }
        Caption("Sets \"today\" and every kick-off time on the Sports screen. The Automatic option uses your IP address.")

        Section("Channel matching")
        ValueRow(
            title = "Preferred countries", desc = countries.ifBlank { "All countries" },
            value = if (countries.isNotBlank()) "Edit" else "Add", focus = null,
        ) {
            TvDialogs.input("Preferred countries", initial = countries, hint = "US, CA, UK") { value ->
                val raw = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                AppPreferences.setSportsPreferredCountries(raw)
                countries = raw.joinToString(", ")
            }
        }
        ValueRow(
            title = "Favourite league", desc = favLeague.ifBlank { "None" },
            value = if (favLeague.isNotBlank()) "Clear" else "Set", focus = null,
        ) {
            if (favLeague.isNotBlank()) { AppPreferences.sportsFavoriteLeague = ""; favLeague = "" }
        }

        Section("Goal alerts")
        val delayLabels = listOf(0 to "Instant", 10 to "10 s", 20 to "20 s", 30 to "30 s", 45 to "45 s", 60 to "60 s")
        delayLabels.forEach { (s, label) ->
            ChoiceRow(title = "Delay · $label", selected = goalDelay == s, focus = null) {
                AppPreferences.goalAlertDelay = s; goalDelay = s
            }
        }
        Caption("A delay avoids spoilers when the stream lags behind the live score.")

        Section("Channel picks")
        val picks = AppPreferences.sportsChannelPicks
        ValueRow(
            title = "Saved picks", desc = if (picks.isNotBlank()) "${picks.length} characters saved" else "No picks saved",
            value = if (picks.isNotBlank()) "Clear" else "—", focus = null,
        ) { if (picks.isNotBlank()) { AppPreferences.sportsChannelPicks = "" } }
        Caption("When you choose a channel for a game on the Sports screen, it is offered first next time.")
    }
}
