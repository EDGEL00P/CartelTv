package com.carteltv.tv.ui.settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carteltv.tv.companion.PhoneCompanionServer
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.glassLight
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.trapFocus

/** Opens the phone-companion QR from any screen (voice, deep link, or code). */
object CompanionOverlayState {
    var shown by mutableStateOf(false)
        private set
    fun open() { shown = true }
    fun close() { shown = false }
}

/**
 * One QR for everything the phone can do: remote pad, typing, voice commands, Home Assistant and
 * provider keys. The LAN server only runs while this is open, and is left alone if Settings already started it.
 */
@Composable
fun CompanionOverlay() {
    if (!CompanionOverlayState.shown) return
    val ctx = LocalContext.current
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val owned = remember { !PhoneCompanionServer.running }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val port = PhoneCompanionServer.start(ctx)
        url = PhoneCompanionServer.activeUrl(ctx)
        failed = port == null || url == null
        // Same entry law as TvDialog: wait frame by frame rather than fire once and give up.
        closeFocus.awaitFocus()
    }
    DisposableEffect(Unit) { onDispose { if (owned) PhoneCompanionServer.stop() } }
    BackHandler { CompanionOverlayState.close() }

    Box(
        Modifier.fillMaxSize().background(ThemeSemantics.scrimModal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .trapFocus()
                .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .background(ThemeSemantics.surfaceModal)
                .glassLight()
                .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .padding(ThemeSpec.Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.companion_title), fontSize = ThemeSpec.Type.titleL, fontWeight = FontWeight.Bold, color = ThemeSemantics.textEmphatic, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.companion_scan_hint), fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xl), verticalAlignment = Alignment.CenterVertically) {
                val link = url
                when {
                    link != null -> QrCode(text = link, cellSize = 6.dp)
                    failed -> Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.companion_start_failed), fontSize = ThemeSpec.Type.body, color = ThemeSemantics.danger, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    else -> Text(androidx.compose.ui.res.stringResource(com.carteltv.tv.R.string.companion_starting), fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    listOf(
                        "Remote pad and keys",
                        "Type on the TV from your phone",
                        "Voice and text commands",
                        "Home Assistant setup",
                        "Provider and API keys",
                        "Import and export your setup",
                    ).forEach { Text("•  $it", fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
            url?.let {
                Spacer(Modifier.height(ThemeSpec.Space.md))
                Text(it, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1)
            }
            Spacer(Modifier.height(ThemeSpec.Space.lg))
            // The overlay's only action, so it takes the brass, like OK on an info dialog.
            ActionButton("Close", filled = true, modifier = Modifier.focusRequester(closeFocus)) { CompanionOverlayState.close() }
        }
    }
}
