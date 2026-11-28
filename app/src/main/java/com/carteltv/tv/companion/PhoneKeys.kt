package com.carteltv.tv.companion

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Phone-to-TV typing. The companion page sends text and key names; the activity replays them as
 * hardware key events, so whichever field has focus receives them exactly like a USB keyboard.
 */
object PhoneKeys {
    private const val MAX_CHARS = 200
    private const val MAX_DELETES = 200

    /** Set by the activity while it is on screen; receives events on any thread. */
    @Volatile var sink: ((KeyEvent) -> Unit)? = null

    /** Deletes [del] characters, then types [text]. Returns how many characters were typed. */
    fun type(text: String, del: Int = 0): Int {
        val out = sink ?: error("TV app is not in the foreground")
        repeat(del.coerceIn(0, MAX_DELETES)) { press(out, KeyEvent.KEYCODE_DEL) }
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.take(MAX_CHARS).toCharArray()) ?: return 0
        events.forEach(out)
        return events.size / 2
    }

    /** [action] is "press" (down + up), or "down"/"up" alone so a held phone button can repeat. */
    fun key(name: String, action: String = "press") {
        val out = sink ?: error("TV app is not in the foreground")
        val code = when (name) {
            "backspace" -> KeyEvent.KEYCODE_DEL
            "enter" -> KeyEvent.KEYCODE_ENTER
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            else -> error("Unknown key")
        }
        val t = SystemClock.uptimeMillis()
        when (action) {
            "down" -> out(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            "up" -> out(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
            "press" -> press(out, code)
            else -> error("Unknown action")
        }
    }

    private fun press(out: (KeyEvent) -> Unit, code: Int) {
        val t = SystemClock.uptimeMillis()
        out(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        out(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
