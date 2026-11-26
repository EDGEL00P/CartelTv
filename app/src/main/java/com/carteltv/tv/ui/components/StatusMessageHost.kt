package com.carteltv.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.delay

/**
 * The one in-app status voice. Android toasts are phone chrome — small, unfocusable,
 * easy to miss at 10 foot — so transient confirmations route through here instead:
 * a themed pill at the bottom of the stage, inside the safe margin, on the house surface.
 * `TransientStatus.show(...)` is callable from anywhere (callbacks, non-compose code).
 */
object TransientStatus {
    var message by mutableStateOf<String?>(null)
        private set
    var epoch by mutableIntStateOf(0)
        private set

    fun show(text: String) {
        message = text
        epoch++
    }

    fun clear() {
        message = null
    }
}

private val STATUS_VISIBLE_MS = 2500L

@Composable
fun StatusMessageHost() {
    Box(Modifier.fillMaxSize().padding(bottom = ThemeSpec.Safe.vertical), contentAlignment = Alignment.BottomCenter) {
        val visible = TransientStatus.message != null
        // Restart the dismissal clock on every new message; a newer message wins.
        LaunchedEffect(TransientStatus.epoch) {
            val shown = TransientStatus.message ?: return@LaunchedEffect
            delay(STATUS_VISIBLE_MS)
            if (TransientStatus.message == shown) TransientStatus.clear()
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(ThemeSpec.Motion.tweenMid()) +
                slideInVertically(ThemeSpec.Motion.tweenMid()) { it / 2 },
            exit = fadeOut(ThemeSpec.Motion.tweenMid()) +
                slideOutVertically(ThemeSpec.Motion.tweenMid()) { it / 2 },
        ) {
            TransientStatus.message?.let { msg ->
                Row(
                    Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .background(ThemeSemantics.surfaceFloat, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                        .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.cozy),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textPrimary)
                }
            }
        }
    }
}
