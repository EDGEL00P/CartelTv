package com.carteltv.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.theme.*
import java.text.DateFormat
import java.util.Date

/**
 * One-line provider health warnings above Live TV and the guide (XCIPTV's maintenance pattern).
 * Shows, in priority order: a non-Active account, a saturated connection line, or an expiry
 * inside a week. The expiry line hides itself for the rest of the day when tapped.
 */
@Composable
fun ProviderStatusBanner() {
    val p = AppPreferences.activeProvider() ?: return
    val h = p.health ?: return
    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())

    val (msg, danger) = when {
        h.isWarning -> "Your ${p.name} account is ${h.status?.lowercase()}. Streams may be refused, so check with your provider." to true
        (h.maxCons ?: 0) > 0 && (h.activeCons ?: 0) > (h.maxCons ?: 0) ->
            "All ${h.maxCons} connections are in use, so streams may stutter." to true
        h.expiryMs > 0 && h.daysLeft in 0..7 -> {
            if (AppPreferences.providerExpiryNoticeDay == today) return
            val when_ = if (h.daysLeft == 0) "ends today" else "ends in ${h.daysLeft} day${if (h.daysLeft == 1) "" else "s"}"
            "${p.name} subscription $when_ (${DateFormat.getDateInstance().format(Date(h.expiryMs))})" to false
        }
        else -> return
    }

    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = ThemeSpec.Space.margin, end = ThemeSpec.Safe.horizontal, top = ThemeSpec.Space.xs, bottom = ThemeSpec.Space.xs)
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact))
            .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
            .background(if (danger) ThemeSemantics.danger.copy(alpha = 0.18f) else ThemeSemantics.surfaceRest)
            .onFocusChanged { focused = it.isFocused }
            .focusRing(focused, RoundedCornerShape(ThemeSpec.Card.cornerSm))
            .clickableNoIndication {
                if (!danger) AppPreferences.providerExpiryNoticeDay = today
            }
            .focusable()
            .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            msg,
            fontSize = ThemeSpec.Type.label,
            fontWeight = FontWeight.Bold,
            color = if (danger) ThemeSemantics.danger else ThemeSemantics.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
