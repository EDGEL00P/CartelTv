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
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.components.glassRowDim
import com.carteltv.tv.ui.components.glassRowText
internal fun remotePage(scope: LazyListScope, focus: FocusRequester, st: RemapUiState) {
    scope.item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

        var sinkOn by remember { mutableStateOf(com.carteltv.tv.data.remap.KeyRemapService.isEnabled(ctx)) }
        var overrideHome by remember { mutableStateOf(AppPreferences.overrideHomeButton) }
        var enhancedHome by remember { mutableStateOf(AppPreferences.enhancedHomeDetection) }
        var overrideBack by remember { mutableStateOf(AppPreferences.overrideBackButton) }
        var startOnBoot by remember { mutableStateOf(AppPreferences.startOnBoot) }
        var showWatchNext by remember { mutableStateOf(AppPreferences.showWatchNext) }

        val canWrite = remember { com.carteltv.tv.engine.stability.HomeAuthority.canWriteSecure(ctx) }
        var grantLine by remember { mutableStateOf<String?>(null) }
        var bindTick by remember { mutableIntStateOf(0) }

        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val wasOn = sinkOn
                    val nowOn = com.carteltv.tv.data.remap.KeyRemapService.isEnabled(ctx)
                    sinkOn = nowOn
                    if (nowOn && (!wasOn || !AppPreferences.accessibilityDefaultsConfigured)) {
                        AppPreferences.accessibilityDefaultsConfigured = true
                        AppPreferences.overrideHomeButton = true
                        AppPreferences.overrideBackButton = true
                        AppPreferences.startOnBoot = true
                        overrideHome = true
                        overrideBack = true
                        startOnBoot = true
                        com.carteltv.tv.data.remap.KeyRemapService.notifyPrefsChanged(ctx)
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Section("Launcher and boot controls")
        ValueRow(
            title = if (sinkOn) "Accessibility service · Active" else "Accessibility service · Inactive",
            desc = when {
                sinkOn -> "The Home override and system-wide remote bindings are in use."
                canWrite -> "Permission available. Select Enable to turn it on."
                else -> "Select to open the accessibility settings. No ADB is needed."
            },
            value = when {
                sinkOn -> "ACTIVE"
                canWrite -> "Enable"
                else -> "Open"
            },
            focus = focus,
        ) {
            if (!sinkOn && canWrite) {
                val svc = "com.carteltv.tv/com.carteltv.tv.data.remap.KeyRemapService"
                val cur = android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ) ?: ""
                runCatching {
                    android.provider.Settings.Secure.putString(
                        ctx.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        if (cur.isBlank()) svc else "$cur:$svc",
                    )
                    android.provider.Settings.Secure.putInt(
                        ctx.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1,
                    )
                    sinkOn = true
                    AppPreferences.accessibilityDefaultsConfigured = true
                    AppPreferences.overrideHomeButton = true
                    overrideHome = true
                    com.carteltv.tv.data.remap.KeyRemapService.notifyPrefsChanged(ctx)
                    grantLine = "The accessibility service is on."
                }
            } else {
                com.carteltv.tv.data.remap.KeyRemapService.openAccessibilitySettings(ctx)
            }
        }
        grantLine?.let { Result(it) }

        ToggleRow(
            title = "Replace the default launcher",
            desc = "Send the default launcher's Home screen back to CartelTV without animation",
            value = overrideHome,
            focus = null,
        ) {
            if (!sinkOn) {
                TransientStatus.show("Turn on the accessibility service first")
                return@ToggleRow
            }
            overrideHome = !overrideHome
            AppPreferences.overrideHomeButton = overrideHome
            com.carteltv.tv.data.remap.KeyRemapService.notifyPrefsChanged(ctx)
        }

        ToggleRow(
            title = "Enhanced home detection",
            desc = "Intercept the Home key directly so the default launcher never flashes",
            value = enhancedHome,
            focus = null,
        ) {
            if (!sinkOn) {
                TransientStatus.show("Turn on the accessibility service first")
                return@ToggleRow
            }
            enhancedHome = !enhancedHome
            AppPreferences.enhancedHomeDetection = enhancedHome
            com.carteltv.tv.data.remap.KeyRemapService.notifyPrefsChanged(ctx)
        }
        Caption("Enhanced home detection may affect the volume button long-press on some remotes. If the default launcher still flashes, turn the accessibility service off and on again.")

        ToggleRow(
            title = "Lock the Back button on Home",
            desc = "Stop Back from exiting CartelTV to the default launcher",
            value = overrideBack,
            focus = null,
        ) {
            overrideBack = !overrideBack
            AppPreferences.overrideBackButton = overrideBack
        }

        ToggleRow(
            title = "Start at boot",
            desc = "Open CartelTV automatically when the device starts",
            value = startOnBoot,
            focus = null,
        ) {
            startOnBoot = !startOnBoot
            AppPreferences.startOnBoot = startOnBoot
        }

        ToggleRow(
            title = "Android TV Watch Next row",
            desc = "Show Android TV Watch Next programmes on Home",
            value = showWatchNext,
            focus = null,
        ) {
            showWatchNext = !showWatchNext
            AppPreferences.showWatchNext = showWatchNext
            com.carteltv.tv.data.watch.WatchNextRepository.invalidate()
        }

        // "Navigation Sounds" removed: the real click-sound gate is AppPreferences.focusSounds
        // (read in Theme.kt); navSounds never had a reader.

        Section("Assigned keys")
        val binds = remember(bindTick, st.capturePending) { AppPreferences.remapBindings() }
        if (binds.isEmpty()) {
            Caption("No keys are assigned yet.")
        } else {
            binds.forEach { b ->
                ValueRow(
                    title = "${com.carteltv.tv.data.remap.KeyRemap.keyLabel(b.keyCode)} · " +
                        if (b.flavor == com.carteltv.tv.data.remap.KeyRemap.Flavor.PLAIN) "Press" else "Hold",
                    desc = com.carteltv.tv.data.remap.KeyRemap.actionById(b.action)?.label ?: b.action,
                    value = "Clear", focus = null,
                ) {
                    AppPreferences.clearRemapBinding(b)
                    com.carteltv.tv.data.remap.KeyRemapService.notifyPrefsChanged(ctx)
                    bindTick++
                }
            }
        }

        Section("New assignment")
        Caption("Select an action, then assign a key to it.")
        var pickedAction by remember { mutableStateOf<String?>(null) }
        com.carteltv.tv.data.remap.KeyRemap.ACTIONS.forEach { a ->
            ChoiceRow(
                title = a.label,
                selected = pickedAction == a.id,
                focus = null,
            ) { pickedAction = a.id }
        }
        ValueRow(
            title = "Assign a key · Short press",
            desc = "The action runs when the key is pressed briefly",
            value = "Assign", focus = null,
        ) {
            pickedAction?.let {
                st.actionForCapture = it
                st.capturePending = com.carteltv.tv.data.remap.KeyRemap.Flavor.PLAIN
            }
        }
        ValueRow(
            title = "Assign a key · Long press",
            desc = "The action runs when the key is held for about one second",
            value = "Assign", focus = null,
        ) {
            pickedAction?.let {
                st.actionForCapture = it
                st.capturePending = com.carteltv.tv.data.remap.KeyRemap.Flavor.LONG
            }
        }
        if (pickedAction == null) Caption("Select an action first.")
    }
}

@Composable
internal fun KeyCaptureOverlay(
    onCaptured: (com.carteltv.tv.data.remap.KeyRemap.Flavor, Int) -> Unit,
    onCancel: () -> Unit,
) {
    var downMs by remember { mutableLongStateOf(0L) }
    val captureFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { suspendRunCatching { captureFocus.awaitFocus() } }
    BackHandler { onCancel() }

    Box(
        Modifier.fillMaxSize().background(ThemeSemantics.scrimModal)
            .focusRequester(captureFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                when {
                    ev.type == KeyEventType.KeyDown -> {
                        if (ev.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_BACK) {
                            onCancel(); true
                        } else {
                            if (downMs == 0L) downMs = System.currentTimeMillis()
                            true
                        }
                    }
                    ev.type == KeyEventType.KeyUp && downMs > 0 -> {
                        val held = System.currentTimeMillis() - downMs
                        downMs = 0L
                        val flavor = if (held >= 800)
                            com.carteltv.tv.data.remap.KeyRemap.Flavor.LONG
                        else com.carteltv.tv.data.remap.KeyRemap.Flavor.PLAIN
                        onCaptured(flavor, ev.key.nativeKeyCode)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Press a key", fontSize = ThemeSpec.Type.title, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text("Press or hold the key you want to assign. Press Back to cancel.",
                fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

