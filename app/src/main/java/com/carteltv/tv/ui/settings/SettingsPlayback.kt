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
internal fun playbackPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Playback")
    scope.item {
        Section(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_section_display))
        var thumbsOn by remember { mutableStateOf(AppPreferences.seekThumbs) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_seek_previews), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_seek_previews_desc),
            value = thumbsOn, focus = focus,
        ) { AppPreferences.seekThumbs = !AppPreferences.seekThumbs; thumbsOn = AppPreferences.seekThumbs }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_seek_previews_caption))
        var heroPreviews by remember { mutableStateOf(AppPreferences.autoTrailers) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_hero_previews),
            desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_hero_previews_desc),
            value = heroPreviews,
            focus = null,
        ) { AppPreferences.autoTrailers = !AppPreferences.autoTrailers; heroPreviews = AppPreferences.autoTrailers }

        Section("Stream lists")
        var autoPlay by remember { mutableStateOf(AppPreferences.streamAutoPlay) }
        listOf(
            com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.MANUAL to "Manual: always show the list",
            com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.FIRST to "Auto-play the best source",
            com.carteltv.tv.data.preferences.StreamPreferences.AutoPlay.REGEX to "Auto-play the first name match",
        ).forEach { (mode, label) ->
            ChoiceRow(title = label, selected = autoPlay == mode, focus = null) {
                autoPlay = mode; AppPreferences.streamAutoPlay = mode
            }
        }
        Caption("Auto-play still offers the list for a moment, so Back can cancel it.")
        ChoiceRow(
            title = "Name match: " + AppPreferences.streamAutoPlayRegex.ifBlank { "not set" },
            selected = false,
            focus = null,
        ) {
            TvDialogs.input(
                "Auto-play name match",
                initial = AppPreferences.streamAutoPlayRegex,
                hint = "1080p|HEVC|GROUP",
                message = "A regular expression matched against the release name.",
                onSubmit = { AppPreferences.streamAutoPlayRegex = it },
            )
        }
        var reuse by remember { mutableStateOf(AppPreferences.streamReuseLastLink) }
        ToggleRow(title = "Reuse last link", desc = "Offers the source this title played last while its link is fresh.", value = reuse, focus = null) {
            AppPreferences.streamReuseLastLink = !AppPreferences.streamReuseLastLink; reuse = AppPreferences.streamReuseLastLink
        }
        var cached by remember { mutableStateOf(AppPreferences.streamCachedOnly) }
        ToggleRow(title = "Cached sources only", desc = "Hides torrents the linked debrid account does not already hold.", value = cached, focus = null) {
            AppPreferences.streamCachedOnly = !AppPreferences.streamCachedOnly; cached = AppPreferences.streamCachedOnly
        }
        var hdr by remember { mutableStateOf(AppPreferences.streamHdrOnly) }
        ToggleRow(title = "HDR / Dolby Vision only", desc = "Keeps only copies tagged HDR or DV.", value = hdr, focus = null) {
            AppPreferences.streamHdrOnly = !AppPreferences.streamHdrOnly; hdr = AppPreferences.streamHdrOnly
        }
        Caption("Resolution and source picks live on the stream list itself and persist.")

        Section(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_section_still_watching))
        var passOut by remember { mutableStateOf(AppPreferences.passOutHours) }
        listOf(0 to "Off", 2 to "After 2 hours", 3 to "After 3 hours", 4 to "After 4 hours", 6 to "After 6 hours").forEach { (h, label) ->
            ChoiceRow(title = label, selected = passOut == h, focus = null) { passOut = h; AppPreferences.passOutHours = h }
        }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_still_watching_caption))

        Section(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_section_up_next))
        var upNext by remember { mutableStateOf(AppPreferences.upNextMode) }
        listOf("early" to "30 seconds before the end", "end" to "At the end of the episode", "off" to "Disabled").forEach { (mode, label) ->
            ChoiceRow(title = label, selected = upNext == mode, focus = null) {
                AppPreferences.upNextMode = mode; upNext = mode
            }
        }

        Section(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_section_playback_options))
        var pClock by remember { mutableStateOf(AppPreferences.playerClock) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_player_clock), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_player_clock_desc),
            value = pClock, focus = null,
        ) { AppPreferences.playerClock = !AppPreferences.playerClock; pClock = AppPreferences.playerClock }
    }
}

internal fun advancedPage(scope: LazyListScope, focus: FocusRequester, onOpen: (SettingsPage) -> Unit) {
    pageHead(scope, "Advanced")
    scope.item {
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_advanced_caption))

        Section(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_section_buffering))
        var buffer by remember { mutableStateOf(AppPreferences.bufferPreset) }
        AppPreferences.BufferPreset.entries.forEachIndexed { idx, bp ->
            ChoiceRow(
                title = bp.label,
                selected = buffer == bp,
                focus = if (idx == 0) focus else null,
            ) {
                buffer = bp
                AppPreferences.bufferPreset = bp
            }
        }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_buffer_caption))

        Section(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_section_display))
        var syncRate by remember { mutableStateOf(AppPreferences.refreshSync) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_refresh_sync), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_refresh_sync_desc),
            value = syncRate, focus = null,
        ) { AppPreferences.refreshSync = !AppPreferences.refreshSync; syncRate = AppPreferences.refreshSync }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_refresh_sync_caption))

        var tunnelOn by remember { mutableStateOf(AppPreferences.tunneling) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_tunneled_video), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_tunneled_video_desc),
            value = tunnelOn, focus = null,
        ) { AppPreferences.tunneling = !AppPreferences.tunneling; tunnelOn = AppPreferences.tunneling }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_tunneled_video_caption))

        var offloadOn by remember { mutableStateOf(AppPreferences.audioOffload) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_audio_offload), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_audio_offload_desc),
            value = offloadOn, focus = null,
        ) { AppPreferences.audioOffload = !AppPreferences.audioOffload; offloadOn = AppPreferences.audioOffload }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_audio_offload_caption))

        var enhanceOn by remember { mutableStateOf(AppPreferences.videoEnhance) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_video_enhance), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_video_enhance_desc),
            value = enhanceOn, focus = null,
        ) { AppPreferences.videoEnhance = !AppPreferences.videoEnhance; enhanceOn = AppPreferences.videoEnhance }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_video_enhance_caption))

        var lowLatency by remember { mutableStateOf(AppPreferences.lowLatencyLive) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_faster_live), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_faster_live_desc),
            value = lowLatency, focus = null,
        ) { AppPreferences.lowLatencyLive = !AppPreferences.lowLatencyLive; lowLatency = AppPreferences.lowLatencyLive }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_faster_live_caption))

        var nativeP7 by remember { mutableStateOf(AppPreferences.dvNativeProfile7) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_dv7), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_dv7_desc),
            value = nativeP7, focus = null,
        ) { AppPreferences.dvNativeProfile7 = !AppPreferences.dvNativeProfile7; nativeP7 = AppPreferences.dvNativeProfile7 }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_dv7_caption))

        Section(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_section_audio))
        var matOn by remember { mutableStateOf(AppPreferences.matTrueHd) }
        ToggleRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_truehd), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_truehd_desc),
            value = matOn, focus = null,
        ) { AppPreferences.matTrueHd = !AppPreferences.matTrueHd; matOn = AppPreferences.matTrueHd }
        Caption(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_truehd_caption))

        Section(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_section_tools))
        ValueRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_controls), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_controls_desc),
            value = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_open), focus = null,
        ) { onOpen(SettingsPage.REMOTE) }
        ValueRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_dns_blocking), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_dns_blocking_desc),
            value = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_open), focus = null,
        ) { onOpen(SettingsPage.DNS) }
        ValueRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_backup_restore), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_backup_restore_desc),
            value = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_open), focus = null,
        ) { onOpen(SettingsPage.CONFIG) }
        ValueRow(
            title = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_diagnostics), desc = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_diagnostics_desc),
            value = androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.settings_open), focus = null,
        ) { onOpen(SettingsPage.DEVICE_CARE) }
    }
}

internal fun providerPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "TV provider")
    scope.item {
        val langCtx = androidx.compose.ui.platform.LocalContext.current
        val provScope = rememberCoroutineScope()
        var credTick by remember { mutableIntStateOf(0) }
        val providers = remember(credTick) { AppPreferences.providers() }
        val activeId = remember(credTick) { AppPreferences.activeProvider()?.id }
        Section("Providers · ${providers.size}")
        if (providers.isEmpty()) {
            PlainRow(
                title = "No provider connected",
                desc = "Add one to fill Live TV, the TV guide and Movies.",
                focus = null,
            )
        }
        providers.forEachIndexed { i, p ->
            ValueRow(
                title = "${p.name}${if (p.id == activeId) " · ACTIVE" else ""}",
                desc = when (p.kind) {
                    com.carteltv.tv.data.provider.ProviderType.XTREAM -> "Xtream · ${p.host} · ${p.user}"
                    com.carteltv.tv.data.provider.ProviderType.M3U -> "M3U · ${com.carteltv.tv.data.provider.Provider.hostLabel(p.host)}"
                    com.carteltv.tv.data.provider.ProviderType.STALKER -> "Stalker · ${com.carteltv.tv.data.provider.Provider.hostLabel(p.host)} · ${p.user}"
                },
                value = "Manage", focus = if (i == 0) focus else null,
            ) { showProviderMenu(langCtx, provScope, p) { credTick++ } }
        }
        ValueRow(
            title = "Add provider",
            desc = "Use an Xtream login, an M3U playlist or a Stalker portal. You can switch between them at any time.",
            value = "Add", focus = if (providers.isEmpty()) focus else null,
        ) { addProvider(langCtx) }
        if (providers.isNotEmpty()) {
            var failoverOn by remember { mutableStateOf(AppPreferences.providerFailover) }
            ToggleRow(
                title = "Automatic failover",
                desc = "Switch to the next provider in the list if the active one stops responding",
                value = failoverOn, focus = null,
            ) { failoverOn = !failoverOn; AppPreferences.providerFailover = failoverOn }
            ValueRow(
                title = "Channel groups",
                desc = "Hide, rename and reorder the live channel groups of the active provider",
                value = "Manage", focus = null,
            ) { showGroupManager(langCtx, provScope) }
        }
        var langOn by remember { mutableStateOf(AppPreferences.englishOnly) }
        var langs by remember { mutableStateOf(AppPreferences.contentLanguages) }
        Section("Content languages")
        ToggleRow(
            title = "Hide other languages",
            desc = "Hides live channels, movies and series in other languages",
            value = langOn, focus = null,
        ) {
            langOn = !langOn; AppPreferences.englishOnly = langOn
            com.carteltv.tv.data.xtream.LiveChannels.invalidate()
        }
        ValueRow(
            title = "Languages to keep",
            desc = langs.joinToString(" · ") { it.label },
            value = "Choose", focus = null,
        ) {
            val all = com.carteltv.tv.data.xtream.LanguageFilter.Lang.selectable
            TvDialogs.multiChoice(
                "Languages to keep", all.map { it.label },
                selected = all.indices.filter { all[it] in langs }.toSet(),
            ) { picked ->
                val chosen = all.filterIndexed { i, _ -> i in picked }.toSet()
                    .ifEmpty { setOf(com.carteltv.tv.data.xtream.LanguageFilter.Lang.EN) }
                AppPreferences.contentLanguages = chosen; langs = chosen
                com.carteltv.tv.data.xtream.LiveChannels.invalidate()
            }
        }
        Caption("Titles whose language is unknown are kept. Changes apply the next time a list loads.")
    }
}

internal fun launcherPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Theme & home")
    scope.item {
        Section("Security")
        var doorTick by remember { mutableIntStateOf(0) }
        val armed = remember(doorTick) { AppPreferences.settingsPinOn && AppPreferences.settingsPinIsSet() }
        ToggleRow(
            title = "Parent PIN",
            desc = if (armed) "On. Required to open Settings and locked channels"
                   else "Off. Select to set a PIN",
            value = armed, focus = focus,
        ) {
            when {
                armed -> { AppPreferences.settingsPinOn = false; doorTick++ }
                AppPreferences.settingsPinIsSet() -> { AppPreferences.settingsPinOn = true; doorTick++ }
                else -> com.carteltv.tv.ui.settings.PinRequests.requestSet?.invoke()
            }
        }
        if (armed) {
            ValueRow(
                title = "Change PIN",
                desc = "Replace the current PIN",
                value = "Edit", focus = null,
            ) { com.carteltv.tv.ui.settings.PinRequests.requestSet?.invoke() }
            ToggleRow(
                title = "Remove PIN", desc = "No PIN will be required to open Settings",
                value = false, focus = null,
            ) {
                AppPreferences.settingsPinClear(); AppPreferences.settingsPinOn = false
                com.carteltv.tv.ui.settings.PinLatch.close("settings")
                doorTick++
            }
        }
        Caption("Apps locked with a PIN: ${AppPreferences.appPins().size}. Hold OK on an app tile to set one.")

        Section("Channels and deep links")
        val layoutCtx = androidx.compose.ui.platform.LocalContext.current
        var appChannelsOn by remember { mutableStateOf(AppPreferences.showAppChannels) }
        ToggleRow(
            title = "App preview channels",
            desc = "Show the channel rows that installed streaming apps provide",
            value = appChannelsOn, focus = null,
        ) {
            appChannelsOn = !appChannelsOn
            AppPreferences.showAppChannels = appChannelsOn
            if (appChannelsOn) com.carteltv.tv.data.provider.ChannelRepository.start(layoutCtx)
        }
        var deepLinkOn by remember { mutableStateOf(AppPreferences.playInStreamingApp) }
        ToggleRow(
            title = "Play in streaming apps",
            desc = "Open Discovery titles directly in Netflix, Apple TV, Prime Video or Disney+",
            value = deepLinkOn, focus = null,
        ) {
            deepLinkOn = !deepLinkOn
            AppPreferences.playInStreamingApp = deepLinkOn
        }

        Section("Icon pack")
        val packs = remember { com.carteltv.tv.data.apps.IconPackRegistry.installed(layoutCtx) }
        var activePack by remember { mutableStateOf(AppPreferences.iconPack) }
        ChoiceRow(title = "Default icons", selected = activePack.isBlank(), focus = null) {
            AppPreferences.iconPack = ""; activePack = ""
            com.carteltv.tv.data.apps.InstalledAppsCache.reload(layoutCtx)
        }
        packs.forEach { pack ->
            ChoiceRow(title = pack.label, selected = activePack == pack.packageName, focus = null) {
                AppPreferences.iconPack = pack.packageName; activePack = pack.packageName
                com.carteltv.tv.data.apps.InstalledAppsCache.reload(layoutCtx)
            }
        }
        if (packs.isEmpty()) Caption("Install an icon pack to change the app icons.")

        Section("Sound")
        var focusSnd by remember { mutableStateOf(AppPreferences.focusSounds) }
        ToggleRow(
            title = "Focus sounds",
            desc = "Play a soft click as focus moves between items",
            value = focusSnd, focus = null,
        ) { AppPreferences.focusSounds = !AppPreferences.focusSounds; focusSnd = AppPreferences.focusSounds }

        Section("Time format")
        var clock24 by remember { mutableStateOf(AppPreferences.clock24) }
        ChoiceRow(title = "12-hour", selected = !clock24, focus = null) { AppPreferences.clock24 = false; clock24 = false }
        ChoiceRow(title = "24-hour", selected = clock24, focus = null) { AppPreferences.clock24 = true; clock24 = true }

    }
}

internal fun addonsPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Add-ons")
    scope.item { ManageAddonsPane(focus) }
}

internal fun youtubePage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "YouTube")
    scope.item {
        var sbOn by remember { mutableStateOf(AppPreferences.youTubeSponsorBlock) }
        var catIdx by remember { mutableStateOf(AppPreferences.youTubeCategoryPreset) }
        var qualIdx by remember { mutableStateOf(AppPreferences.youTubeQuality) }
        var codec by remember { mutableStateOf(AppPreferences.youTubeCodec) }
        var speed by remember { mutableStateOf(AppPreferences.youTubeSpeed) }
        var dislikes by remember { mutableStateOf(AppPreferences.youTubeDislikes) }
        var watched by remember { mutableStateOf(AppPreferences.youTubeHideWatched) }
        var minSecs by remember { mutableStateOf(AppPreferences.youTubeMinSeconds) }
        var words by remember { mutableStateOf(AppPreferences.youTubeBlockWords) }
        var chans by remember { mutableStateOf(AppPreferences.youTubeBlockChannels) }
        var editing by remember { mutableStateOf(0) }
        val qualities = listOf("Up to 8K", "720p", "360p Single")
        val codecLabels = com.carteltv.tv.data.youtube.YouTubeApiClient.CODEC_LABELS
        val minSteps = listOf(0, 61, 180, 300, 600)
        val speedSteps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

        Section("Playback")
        ValueRow(
            title = "Target resolution",
            desc = "Preferred stream quality",
            value = qualities[qualIdx.coerceIn(0, 2)], focus = focus,
        ) {
            AppPreferences.youTubeQuality = (AppPreferences.youTubeQuality + 1) % 3
            qualIdx = AppPreferences.youTubeQuality
        }
        ValueRow(
            title = "Preferred codec",
            desc = "Chooses between formats that have the same resolution",
            value = codecLabels[codec.coerceIn(0, codecLabels.lastIndex)], focus = null,
        ) {
            codec = (codec + 1) % codecLabels.size
            AppPreferences.youTubeCodec = codec
        }
        ValueRow(
            title = "Default speed",
            desc = "The speed each video starts at. Fast-forward and rewind change it during playback",
            value = "${speed}x", focus = null,
        ) {
            speed = speedSteps[(speedSteps.indexOfFirst { it >= speed - 0.01f }.coerceAtLeast(0) + 1) % speedSteps.size]
            AppPreferences.youTubeSpeed = speed
        }
        ToggleRow(
            title = "Dislike count",
            desc = "Show likes and estimated dislikes when a video starts",
            value = dislikes, focus = null,
        ) { AppPreferences.youTubeDislikes = !AppPreferences.youTubeDislikes; dislikes = AppPreferences.youTubeDislikes }

        Section("Segment bypass")
        ToggleRow(
            title = "Segment bypass",
            desc = "Skip known non-content segments",
            value = sbOn, focus = null,
        ) { AppPreferences.youTubeSponsorBlock = !AppPreferences.youTubeSponsorBlock; sbOn = AppPreferences.youTubeSponsorBlock }
        ValueRow(
            title = "Category filter",
            desc = "The categories that are skipped",
            value = YouTubeCategoryPresets.label(catIdx), focus = null,
        ) {
            AppPreferences.youTubeCategoryPreset = (AppPreferences.youTubeCategoryPreset + 1) % YouTubeCategoryPresets.size()
            catIdx = AppPreferences.youTubeCategoryPreset
        }

        Section("Feed filters")
        ToggleRow(
            title = "Hide watched videos",
            desc = "Remove videos you have already watched from feeds",
            value = watched, focus = null,
        ) { AppPreferences.youTubeHideWatched = !AppPreferences.youTubeHideWatched; watched = AppPreferences.youTubeHideWatched }
        ValueRow(
            title = "Minimum length",
            desc = "Hide videos shorter than this length, including Shorts",
            value = when {
                minSecs == 0 -> "Off"
                minSecs < 120 -> "1 min"
                else -> "${minSecs / 60} min"
            }, focus = null,
        ) {
            minSecs = minSteps[(minSteps.indexOf(minSecs).coerceAtLeast(0) + 1) % minSteps.size]
            AppPreferences.youTubeMinSeconds = minSecs
        }
        ValueRow(
            title = "Blocked words", desc = words.ifBlank { "Hide videos whose titles contain any of these words" },
            value = if (words.isNotBlank()) "Edit" else "Add", focus = null,
        ) { editing = 1 }
        ValueRow(
            title = "Blocked channels", desc = chans.ifBlank { "Hide videos from these channels. The name must match exactly" },
            value = if (chans.isNotBlank()) "Edit" else "Add", focus = null,
        ) { editing = 2 }
        if (editing != 0) {
            val isWords = editing == 1
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { editing = 0 },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                SettingsCodeDialog(
                    heading = if (isWords) "Blocked words (separated by commas)" else "Blocked channels (exact names, separated by commas)",
                    initial = if (isWords) words else chans,
                    readOnly = false, primary = "Save",
                    onClear = {
                        if (isWords) { AppPreferences.youTubeBlockWords = ""; words = "" } else { AppPreferences.youTubeBlockChannels = ""; chans = "" }
                        editing = 0
                    },
                ) { typed ->
                    if (typed.isNotBlank()) {
                        val clean = com.carteltv.tv.data.youtube.YouTubeFeedFilter.parseList(typed).joinToString(", ")
                        if (isWords) { AppPreferences.youTubeBlockWords = clean; words = clean } else { AppPreferences.youTubeBlockChannels = clean; chans = clean }
                    }
                    editing = 0
                }
            }
        }
    }
}

internal fun recordingsPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Recordings")
    scope.item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        var rev by remember { mutableIntStateOf(0) }
        val recs = remember(rev) { com.carteltv.tv.recording.RecordingService.listRecordings(ctx) }
        val scheds = remember(rev) { com.carteltv.tv.recording.RecordingScheduler.all(ctx) }
        val total = remember(recs) { recs.sumOf { it.length() } / 1e6 }
        if (recs.isEmpty()) {
            PlainRow(title = "Recordings", desc = "No recordings yet. Start one from a live channel.", focus = focus)
        } else {
            Section("Recordings · ${recs.size} · %.1f MB".format(total))
            recs.take(6).forEachIndexed { idx, f ->
                ValueRow(
                    title = f.nameWithoutExtension,
                    desc = "%.1f MB · %s".format(f.length() / 1e6,
                        java.text.SimpleDateFormat("MMM d HH:mm", locale).format(java.util.Date(f.lastModified()))),
                    value = "Open", focus = if (idx == 0) focus else null,
                ) {
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            ctx, "com.carteltv.tv.fileprovider", f,
                        )
                        AppLauncher.player(ctx, uri.toString(), f.nameWithoutExtension, "recording:${f.name}")
                    }
                }
            }
        }
        if (scheds.isNotEmpty()) {
            Section("Scheduled · ${scheds.size}")
            scheds.sortedBy { it.startMs }.take(8).forEach { job ->
                val when_ = java.text.SimpleDateFormat("EEE d MMM HH:mm", locale).format(java.util.Date(job.startMs))
                ValueRow(
                    title = job.title,
                    desc = "$when_ · %.0f min".format(job.durationMs / 60000.0),
                    value = "Cancel", focus = null,
                ) {
                    com.carteltv.tv.recording.RecordingScheduler.cancel(ctx, job.id)
                    rev++
                    TransientStatus.show("Cancelled · ${job.title}")
                }
            }
        }
    }
    scope.item {
        val padSteps = intArrayOf(0, 1, 2, 5, 10)
        var padBefore by remember { mutableIntStateOf(AppPreferences.recPadBeforeMin) }
        var padAfter by remember { mutableIntStateOf(AppPreferences.recPadAfterMin) }
        fun nextPad(current: Int): Int {
            val at = padSteps.indexOf(current).let { if (it < 0) 0 else it }
            return padSteps[(at + 1) % padSteps.size]
        }
        Section("Recording padding")
        ValueRow(
            title = "Start earlier",
            desc = "Start every scheduled recording this many minutes early",
            value = "$padBefore min", focus = null,
        ) {
            padBefore = nextPad(padBefore)
            AppPreferences.recPadBeforeMin = padBefore
        }
        ValueRow(
            title = "End later",
            desc = "Keep recording this many minutes after the programme ends",
            value = "$padAfter min", focus = null,
        ) {
            padAfter = nextPad(padAfter)
            AppPreferences.recPadAfterMin = padAfter
        }
    }
}

