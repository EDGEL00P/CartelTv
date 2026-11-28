@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.carteltv.tv.ui.nav
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.clickableTv
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.components.tryFocus
import com.carteltv.tv.ui.settings.PinGate
import com.carteltv.tv.ui.theme.ThemePrimitives
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec

/*
 * The long-press menu and PIN prompt that every app shelf raises. Shell-level overlay
 * infrastructure: Home hosts it, and any screen that shows an app shelf raises through it.
 */

internal data class HomeMenuSpec(
    val title: String,
    val verbs: List<Pair<String, () -> Unit>>,
)

internal sealed interface HomePinSpec {
    val app: LauncherApp
    data class Unlock(override val app: LauncherApp, val onUnlocked: () -> Unit) : HomePinSpec
    data class Clear(override val app: LauncherApp) : HomePinSpec
    data class Set(override val app: LauncherApp) : HomePinSpec
}

/**
 * Hosts the app context menu and PIN gate. Each screen that shows a shelf has to call this,
 * or those requests are silently dropped.
 */
@Composable
internal fun AppOverlayHost(
    menu: HomeMenuSpec?,
    onMenuDismiss: () -> Unit,
    pin: HomePinSpec?,
    onPinClose: () -> Unit,
    onChanged: () -> Unit,
) {
    menu?.let { spec -> ContextMenu(title = spec.title, verbs = spec.verbs, onDismiss = onMenuDismiss) }
    pin?.let { spec ->
        when (spec) {
            is HomePinSpec.Unlock -> PinGate(
                title = spec.app.label,
                onOk = { entered ->
                    if (AppPreferences.verifyAppPin(spec.app.packageName, entered)) {
                        onPinClose()
                        spec.onUnlocked()
                        true
                    } else false
                },
                onCancel = onPinClose,
            )
            is HomePinSpec.Clear -> PinGate(
                title = "Remove PIN for ${spec.app.label}",
                onOk = { entered ->
                    if (AppPreferences.verifyAppPin(spec.app.packageName, entered)) {
                        AppPreferences.clearAppPin(spec.app.packageName)
                        onPinClose()
                        onChanged()
                        true
                    } else false
                },
                onCancel = onPinClose,
            )
            is HomePinSpec.Set -> PinGate(
                title = "Set PIN for ${spec.app.label}",
                onOk = { entered ->
                    if (entered.length in 1..8) {
                        AppPreferences.setAppPin(spec.app.packageName, entered)
                        onPinClose()
                        onChanged()
                        true
                    } else false
                },
                onCancel = onPinClose,
            )
        }
    }
}

internal fun appMenuSpec(
    ctx: Context,
    app: LauncherApp,
    isLocked: Boolean,
    onClick: () -> Unit,
    onRequestPin: (HomePinSpec) -> Unit,
    onChanged: () -> Unit,
): HomeMenuSpec = HomeMenuSpec(
    title = app.label,
    verbs = listOf(
        "Open" to { if (isLocked) onRequestPin(HomePinSpec.Unlock(app, onClick)) else onClick() },
        "App info" to {
            runCatching {
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${app.packageName}"))
                    .let { ctx.startActivity(it) }
            }
        },
        "Force stop" to {
            runCatching {
                ctx.getSystemService(android.app.ActivityManager::class.java)
                    ?.killBackgroundProcesses(app.packageName)
            }
        },
        if (isLocked) "Remove PIN lock" to { onRequestPin(HomePinSpec.Clear(app)) }
        else "Lock with PIN" to { onRequestPin(HomePinSpec.Set(app)) },
        "Hide app" to {
            AppPreferences.setHiddenApps(AppPreferences.hiddenApps() + app.packageName)
            onChanged()
        },
    ),
)

@Composable
private fun ContextMenu(
    title: String,
    verbs: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val firstVerbRef = remember { FocusRequester() }
    var verbFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Focus races the composition on cold opens; keep claiming until a verb actually holds it.
        var tries = 0
        while (!verbFocused && tries < 240) {
            firstVerbRef.tryFocus()
            kotlinx.coroutines.delay(50)
            tries++
        }
    }
    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .focusProperties { onEnter = { firstVerbRef } }
            // The one dialog veil, so the menu dims the stage exactly as the PIN gate and onboarding do.
            .background(ThemeSemantics.scrimModal)
            .clickableTv(hoverEffect = false, showHand = false) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(ThemeSpec.Layout.dialogCompact)
                .clip(RoundedCornerShape(ThemeSpec.Card.corner))
                .background(ThemeSemantics.surfaceRest)
                .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.corner))
                .clickableTv(hoverEffect = false, showHand = false) { }
                .padding(vertical = ThemeSpec.Space.sm),
        ) {
            Text(
                title, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            verbs.forEachIndexed { idx, (label, action) ->
                var focused by remember { mutableStateOf(false) }
                Text(
                    label, fontSize = ThemeSpec.Type.body,
                    color = if (focused) ThemeSemantics.accentBright else ThemeSemantics.textPrimary,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (idx == 0) Modifier.focusRequester(firstVerbRef) else Modifier)
                        .glassRow(focused)
                        .onFocusChanged { focused = it.isFocused; if (it.isFocused) verbFocused = true }
                        .clickableNoIndication { action(); onDismiss() }
                        .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.sm), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}