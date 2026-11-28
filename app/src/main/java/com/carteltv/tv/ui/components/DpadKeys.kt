package com.carteltv.tv.ui.components
import android.os.SystemClock
import android.view.KeyEvent

/** D-pad input rules shared by the Activity's key dispatch. */
object DpadKeys {
    private const val STALE_REPEAT_MS = 500L

    fun isDirection(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    /** Input and multi-choice dialogs need Compose's native caret and focus traversal; menus use selection keys. */
    fun usesNativeDialogTraversal(spec: TvDialogSpec?): Boolean =
        spec is TvInputSpec || spec is TvChoiceSpec

    /** A held-arrow repeat queued while the UI was busy; dropped so scrolling never runs away. */
    fun isStaleRepeat(event: KeyEvent): Boolean =
        event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount > 0 &&
            (isDirection(event.keyCode) || isActivation(event.keyCode)) &&
            SystemClock.uptimeMillis() - event.eventTime > STALE_REPEAT_MS

    private fun isActivation(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BACK
}
