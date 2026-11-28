package com.carteltv.tv.ui.settings
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.ThemeSemantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.ui.theme.*
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.clickableTv
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.cardSurface

/**
 * In-memory session unlock latch. Resets automatically on process restart.
 * Backed by Compose snapshot state so locked screens recompose immediately on unlock.
 */
object PinLatch {
    private val unlockedZones = mutableStateMapOf<String, Boolean>()

    fun isOpen(zone: String): Boolean = unlockedZones[zone] == true
    fun open(zone: String) { unlockedZones[zone] = true }
    fun close(zone: String) { unlockedZones.remove(zone) }
}

/** Cross-composable hook allowing nested settings rows to request the SET-PIN overlay. */
object PinRequests {
    @Volatile var requestSet: (() -> Unit)? = null
}

private const val MAX_PIN_LENGTH = 8

@Composable
fun PinGate(
    title: String,
    onOk: (pin: String) -> Boolean,
    onCancel: () -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val lockSec by produceState(0L, error) {
        while (error) {
            value = (com.carteltv.tv.data.preferences.AppPreferences.pinLockRemainingMs() + 999) / 1000
            if (value <= 0L) break
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val firstKeyRef = remember { FocusRequester() }

    LaunchedEffect(Unit) { suspendRunCatching { firstKeyRef.awaitFocus() } }

    fun appendDigit(d: String) {
        if (entered.length < MAX_PIN_LENGTH) {
            entered += d
            error = false
        }
    }

    fun submit() {
        if (entered.isBlank()) return
        if (onOk(entered)) return
        error = true
        entered = ""
    }

    BackHandler { onCancel() }

    Box(
        Modifier
            .fillMaxSize()
            .trapFocus()
            .background(ThemeSemantics.scrimModal)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.One -> { appendDigit("1"); true }
                    Key.Two -> { appendDigit("2"); true }
                    Key.Three -> { appendDigit("3"); true }
                    Key.Four -> { appendDigit("4"); true }
                    Key.Five -> { appendDigit("5"); true }
                    Key.Six -> { appendDigit("6"); true }
                    Key.Seven -> { appendDigit("7"); true }
                    Key.Eight -> { appendDigit("8"); true }
                    Key.Nine -> { appendDigit("9"); true }
                    Key.Zero -> { appendDigit("0"); true }
                    Key.Backspace, Key.Delete -> {
                        if (entered.isNotEmpty()) entered = entered.dropLast(1)
                        true
                    }
                    else -> false
                }
            }
            .clickableTv(hoverEffect = false, showHand = false) { onCancel() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .background(ThemeSemantics.surfaceModal)
                .glassLight()
                .border(1.dp, if (error) ThemeSemantics.danger else ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .clickableTv(hoverEffect = false, showHand = false) { }
                .padding(ThemeSpec.Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("•  •  •", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Text(
                "Parent PIN · $title",
                fontSize = ThemeSpec.Type.body,
                fontWeight = FontWeight.Bold,
                color = if (error) ThemeSemantics.danger else ThemeSemantics.textEmphatic,
                textAlign = TextAlign.Center, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(ThemeSpec.Space.xs))
            Text(
                if (error) {
                    if (lockSec > 0) "Too many attempts. Try again in ${lockSec} s" else "Incorrect PIN"
                } else "Enter PIN",
                fontSize = ThemeSpec.Type.label,
                color = if (error) ThemeSemantics.danger else ThemeSemantics.textSecondary,
            )
            Spacer(Modifier.height(ThemeSpec.Space.md))
            val masked = "•".repeat(entered.length).padEnd(maxOf(4, entered.length), '·')
            Text(masked, fontSize = ThemeSpec.Type.titleL, color = ThemeSemantics.accent, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            Keypad(
                firstKeyRef = firstKeyRef,
                onDigit = ::appendDigit,
                onBackspace = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
            )
            Spacer(Modifier.height(ThemeSpec.Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
                ActionButton("Cancel", filled = false) { onCancel() }
                ActionButton("Enter", filled = true) { submit() }
            }
        }
    }
}

@Composable
private fun Keypad(
    firstKeyRef: FocusRequester,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("0", "⌫"),
        )
        rows.forEachIndexed { rIdx, rowKeys ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowKeys.forEachIndexed { cIdx, label ->
                    val mod = if (rIdx == 0 && cIdx == 0) Modifier.focusRequester(firstKeyRef) else Modifier
                    KeyCap(label, mod) {
                        if (label == "⌫") onBackspace() else onDigit(label)
                    }
                }
            }
            Spacer(Modifier.height(ThemeSpec.Space.sm))
        }
    }
}

@Composable
private fun KeyCap(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        fontSize = ThemeSpec.Type.body,
        color = if (focused) ThemeSemantics.textInverse else ThemeSemantics.textEmphatic,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .size(width = 52.dp, height = 48.dp)
            .cardSurface(focused)
            .background(if (focused) ThemeSemantics.accent else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickableNoIndication { onClick() }
            .wrapContentSize(Alignment.Center), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}
