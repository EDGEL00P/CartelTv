package com.carteltv.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.carteltv.tv.ui.components.TvDialogHost
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.multiview.MultiViewScreen
import com.carteltv.tv.ui.player.PlayerRemote
import com.carteltv.tv.ui.theme.CartelTheme

/**
 * Multi-screen live TV: up to four tunings on one screen, audio on the focused tile.
 * Receives an optional seed tuning (the channel the viewer came from).
 */
class MultiViewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize()) {
                    MultiViewScreen(
                        seedRefId = intent.getStringExtra("refId") ?: "",
                        seedTitle = intent.getStringExtra("title") ?: "",
                        seedUrl = intent.getStringExtra("url") ?: "",
                        onClosed = { finish() },
                    )
                    // The screen's Save and Load layouts go through TvDialogs, which only this host draws.
                    TvDialogHost()
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While a TV dialog is up it owns the D-pad: keys route into its selection model, as in MainActivity.
        if (TvDialogs.current != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val handled = TvDialogs.dispatchKey(event.keyCode)
                if (handled || event.keyCode != KeyEvent.KEYCODE_BACK) return true
            } else if (event.keyCode != KeyEvent.KEYCODE_BACK) {
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            // MENU belongs to the players on screen; it never opens the legacy options panel.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) PlayerRemote.toggleActiveDeck()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
