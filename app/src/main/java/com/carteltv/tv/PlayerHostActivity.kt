package com.carteltv.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.theme.CartelTheme
import com.carteltv.tv.ui.player.PlayerScreenContent
import com.carteltv.tv.ui.player.PlayerRemote

/**
 * Dedicated full-screen player Activity. Keeps screen awake during playback.
 */
class PlayerHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Android 11: asks the TV for its low-latency / minimal-processing mode (HDMI ALLM) while playing.
        if (display?.isMinimalPostProcessingSupported == true) window.setPreferMinimalPostProcessing(true)
        val url = intent.getStringExtra("url") ?: return finish()
        val title = intent.getStringExtra("title") ?: ""
        val refId = intent.getStringExtra("refId") ?: ""
        val alternates = com.carteltv.tv.engine.playback.AlternateSourceSelector.decode(intent.getStringExtra("alternates"))

        AppPreferences.addRecent("$refId|$title|$url")

        setContent {
            CartelTheme {
                PlayerScreenContent(
                    streamUrl = url,
                    title = title,
                    refId = refId,
                    alternates = alternates,
                    onClosed = { finish() },
                )
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            // MENU shows or hides the player's deck; consumed either way, so no legacy options panel opens.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) PlayerRemote.toggleActiveDeck()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
