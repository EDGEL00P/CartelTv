package com.carteltv.tv.ui.nav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.delay

/** Last remote input; shell glass eases down when the viewer has been quiet for this long. */
object ShellIdleTimer {
    const val HIDE_AFTER_MS = 6_000L
    var lastInputMs by mutableLongStateOf(0L)
        private set

    fun poke() { lastInputMs = android.os.SystemClock.uptimeMillis() }
}

/** How far shell glass eases down once the remote has been quiet: still readable, no longer in the way. */
private const val IDLE_ALPHA = 0.45f

/** False once the remote has been quiet for [ShellIdleTimer.HIDE_AFTER_MS]; any key press makes it true again. */
@Composable
internal fun rememberRemoteAwake(): Boolean {
    var awake by remember { mutableStateOf(true) }
    LaunchedEffect(ShellIdleTimer.lastInputMs) {
        awake = true
        delay(ShellIdleTimer.HIDE_AFTER_MS)
        awake = false
    }
    return awake
}

/**
 * The opacity shell glass (the nav bar and header actions) should wear right now: full while the remote is in use,
 * easing to a translucent ghost when it is not. Read it inside `graphicsLayer { alpha = ... }` so the fade
 * animates in the draw layer without recomposing the content.
 */
@Composable
internal fun rememberShellGlassAlpha(): androidx.compose.runtime.State<Float> =
    animateFloatAsState(
        targetValue = if (rememberRemoteAwake()) 1f else IDLE_ALPHA,
        animationSpec = tween(ThemeSpec.Motion.DUR_SLOW),
        label = "shellGlass",
    )
