package com.carteltv.tv.ui.components

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DpadKeysTest {

    private fun repeated(keyCode: Int, ageMs: Long = 1_000L) = KeyEvent(
        0L,
        SystemClock.uptimeMillis() - ageMs,
        KeyEvent.ACTION_DOWN,
        keyCode,
        1,
    )

    @Test fun staleNavigationAndActivationRepeatsAreDropped() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK,
        ).forEach { keyCode ->
            assertTrue("keyCode $keyCode", DpadKeys.isStaleRepeat(repeated(keyCode)))
        }
    }

    @Test fun freshAndInitialPressesPassThrough() {
        assertFalse(DpadKeys.isStaleRepeat(repeated(KeyEvent.KEYCODE_DPAD_CENTER, ageMs = 10L)))
        val initial = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse(DpadKeys.isStaleRepeat(initial))
    }

    @Test fun keyUpIsNeverDroppedAsARepeat() {
        val keyUp = KeyEvent(
            0L,
            SystemClock.uptimeMillis() - 1_000L,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_DPAD_CENTER,
            1,
        )
        assertFalse(DpadKeys.isStaleRepeat(keyUp))
    }

    @Test fun inputAndChoiceDialogsUseNativeTraversal() {
        val input = TvInputSpec("Input", null, "", "Hint", "Save", "Cancel", null, null, null) {}
        val choice = TvChoiceSpec("Choices", null, listOf("One"), emptySet(), "Save", "Cancel", null) {}
        assertTrue(DpadKeys.usesNativeDialogTraversal(input))
        assertTrue(DpadKeys.usesNativeDialogTraversal(choice))
    }

    @Test fun selectionDialogsKeepSelectionTraversal() {
        val menu = TvMenuSpec("Menu", null, listOf("One"), "Cancel", null) {}
        val confirm = TvConfirmSpec("Confirm", null, "OK", "Cancel", false, null) {}
        val info = TvInfoSpec("Info", null, "OK")
        assertFalse(DpadKeys.usesNativeDialogTraversal(menu))
        assertFalse(DpadKeys.usesNativeDialogTraversal(confirm))
        assertFalse(DpadKeys.usesNativeDialogTraversal(info))
    }
}
