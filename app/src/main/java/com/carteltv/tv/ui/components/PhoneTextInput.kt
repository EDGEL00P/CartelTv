package com.carteltv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.companion.PhoneCompanionServer
import com.carteltv.tv.ui.settings.QrCode
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.delay

/**
 * Which text field wants typing right now. Every [TvTextField] reports here, so one pill can offer
 * "type this from your phone" for every field in the app without each screen wiring it up.
 */
object PhoneTyping {
    var label by mutableStateOf<String?>(null)
        private set
    private var owner: Any? = null

    fun enter(
        who: Any,
        label: String,
    ) {
        owner = who
        this.label = label
    }

    /** Only the field that last took the pill may clear it, so focus moving between fields does not flicker. */
    fun leave(who: Any) {
        if (owner === who) {
            owner = null
            label = null
        }
    }
}

private const val LINGER_MS = 20_000L

/**
 * QR for the phone's "Type on TV" page, shown while a text field has focus. The LAN server is started
 * for it and stopped shortly after the field loses focus, unless Settings or the login screen already
 * runs it, in which case it is left alone.
 */
@Composable
fun PhoneTypePill(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val label = PhoneTyping.label
    val shown = label != null
    var url by remember { mutableStateOf<String?>(null) }
    var owned by remember { mutableStateOf(false) }

    LaunchedEffect(shown) {
        if (shown) {
            if (PhoneCompanionServer.isSessionExpired()) {
                PhoneCompanionServer.stop()
                owned = false
            }
            if (!PhoneCompanionServer.running && PhoneCompanionServer.start(ctx) != null) owned = true
            url = PhoneCompanionServer.activeUrl(ctx)?.let { "$it&m=type" }
        } else {
            delay(LINGER_MS)
            if (owned) {
                PhoneCompanionServer.stop()
                owned = false
            }
            url = null
        }
    }
    DisposableEffect(Unit) { onDispose { if (owned) PhoneCompanionServer.stop() } }

    val link = url
    if (!shown || link == null) return
    Row(
        modifier
            .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
            .background(ThemeSemantics.surfaceModal)
            .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg))
            .padding(ThemeSpec.Space.md),
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrCode(text = link, cellSize = 4.dp)
        Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
            Text(
                "Type on your phone",
                fontSize = ThemeSpec.Type.meta,
                fontWeight = FontWeight.SemiBold,
                color = ThemeSemantics.textEmphatic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Scan, then paste or type: $label",
                fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
