package com.carteltv.tv.ui.components
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import com.carteltv.tv.ui.apps.AllAppsOverlayState
import com.carteltv.tv.ui.nav.ShellChrome

/*
 * Focus rules for the whole app:
 *  - Movement is Compose's native 2D search. No screen wires up/down/left/right targets by hand,
 *    because a target that is not attached to a node makes the search throw.
 *  - A screen that wants initial focus marks exactly one node with [entryFocus].
 *  - A modal marks its root with [trapFocus]; focus can neither leave it nor start under it.
 */

/** Requests focus. Returns false while the target is not attached to the focus tree yet. */
fun FocusRequester.tryFocus(): Boolean =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        false
    }

/** Waits frame by frame until the node attaches and accepts focus; false if it never does. */
suspend fun FocusRequester.awaitFocus(maxFrames: Int = 12): Boolean {
    repeat(maxFrames) {
        withFrameNanos { }
        if (tryFocus()) return true
    }
    return false
}

/**
 * Marks the screen's entry node: it takes focus when [key] changes (and on first composition).
 * One per screen; pass [enabled] = false while something else owns focus. Pass [requester] when
 * a parent (for example a row restorer) needs the same node as its fallback target.
 */
@Composable
fun Modifier.entryFocus(
    key: Any? = Unit,
    enabled: Boolean = true,
    requester: FocusRequester = remember { FocusRequester() },
): Modifier {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(key, enabled) {
        if (enabled && !requester.awaitFocus()) focusManager.moveFocus(FocusDirection.Enter)
    }
    return this
        .onFocusChanged { if (it.hasFocus) ShellChrome.lastContentFocus = requester }
        .focusRequester(requester)
}

/** Re-entering a horizontal row from above or below lands on the item focused last, not on the first one. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.restoreRowFocus(): Modifier = this.focusRestorer()

/*
 * Identity-based focus memory. `focusRestorer` only survives while the composition lives;
 * a Nav push disposes the screen, so Back lands on the entry node instead of the title the
 * user opened. FocusMemoryStore persists the stable content key per zone so a returning screen
 * can scroll the remembered item into view and focus it by identity, not index.
 */

/** Last focused content key per screen zone. In-memory only: a fresh process starts clean. */
object FocusMemoryStore {
    private val last = mutableMapOf<String, String>()

    /** Ignores null so callers can pass lookup results straight through. */
    fun remember(zone: String, key: String?) {
        if (key != null) last[zone] = key
    }

    fun peek(zone: String): String? = last[zone]

    fun clear(zone: String) {
        last.remove(zone)
    }
}

/** Focus requesters for items currently composed, keyed by the same content key as FocusMemoryStore. */
@Composable
fun rememberKeyFocusRegistry(): MutableMap<String, FocusRequester> = remember { mutableMapOf() }

/**
 * Marks a lazy grid/row item as restorable under [zone] with the stable [key]:
 * records focus into [FocusMemoryStore] and registers its requester for identity restore.
 */
@Composable
fun Modifier.keyFocusTarget(
    registry: MutableMap<String, FocusRequester>,
    zone: String,
    key: String,
    requester: FocusRequester = remember { FocusRequester() },
): Modifier {
    DisposableEffect(key) {
        registry[key] = requester
        onDispose { if (registry[key] === requester) registry.remove(key) }
    }
    return this
        .onFocusChanged {
            if (it.hasFocus) {
                FocusMemoryStore.remember(zone, key)
                ShellChrome.lastContentFocus = requester
            }
        }
        .focusRequester(requester)
}

/**
 * Restores focus by content identity: scrolls [seek] to the remembered key's index in [keys],
 * waits for layout attachment, then focuses it. Returns false when nothing was remembered or
 * the item is gone from the current data (deterministic fallback: the screen's entry focus).
 */
suspend fun restoreKeyFocus(
    zone: String,
    keys: List<String>,
    registry: Map<String, FocusRequester>,
    seek: suspend (Int) -> Unit,
): Boolean {
    val key = FocusMemoryStore.peek(zone) ?: return false
    val index = keys.indexOf(key)
    if (index < 0) return false
    seek(index)
    // Two frames: the first settles the scroll, the second attaches the focused node.
    withFrameNanos { }
    withFrameNanos { }
    return registry[key]?.awaitFocus() ?: false
}

/** Arbitration for modal surfaces. Blocking ones (kids veil, profile picker, PIN gate) always win. */
object OverlayCoordinator {
    /** True while a blocking surface owns the screen. */
    var locked by mutableStateOf(false)

    /** Bumped when a modal that held focus leaves composition; the shell restores content focus. */
    var restoreTick by mutableStateOf(0)

    fun canOpenTransient(): Boolean = !locked

    fun anyOpen(): Boolean = locked || AllAppsOverlayState.open || TvDialogs.current != null

    fun closeTransient() {
        AllAppsOverlayState.hide()
    }
}

/** Keeps D-pad focus inside a modal and asks the shell to restore content focus when it closes. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Modifier.trapFocus(): Modifier {
    val everFocused = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (everFocused[0]) OverlayCoordinator.restoreTick++ }
    }
    return this
        .onFocusChanged { if (it.hasFocus) everFocused[0] = true }
        .focusProperties { onExit = { cancelFocusChange() } }
        .focusGroup()
}

/** Full-screen tap-to-dismiss backdrop. Uses pointer input, not clickable, so `canFocus = false`
    never lands on this node — that flag would inherit down and make the whole dialog subtree
    unfocusable (the bug that left TV dialogs deaf to the D-pad). */
fun Modifier.dismissScrim(onDismiss: () -> Unit): Modifier = this
    .pointerInput(Unit) { detectTapGestures { onDismiss() } }

/** Swallows taps on panel content so they never reach the backdrop behind it. */
fun Modifier.swallowTaps(): Modifier = this
    .pointerInput(Unit) { detectTapGestures { } }