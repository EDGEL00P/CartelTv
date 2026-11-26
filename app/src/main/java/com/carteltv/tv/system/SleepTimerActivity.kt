package com.carteltv.tv.system
import com.carteltv.tv.ui.theme.ThemeSpec
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.AppScope
import com.carteltv.tv.engine.stability.NotificationMirror
import com.carteltv.tv.ui.components.rememberClock
import com.carteltv.tv.ui.player.NowPlayingState
import com.carteltv.tv.ui.theme.CartelTheme
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemePrimitives
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SLEEP TIMER: the sleep timer's
 * destination — a full-bleed clock the TV dims into. Any key finishes
 * it. Falls far short of true standby (that needs accessibility or
 * elevated `input keyevent POWER`, offered later via HomeAuthority).
 */
class SleepTimerActivity : ComponentActivity() {

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()   // any key wakes the room
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CartelTheme {
                val now = rememberClock()
                Box(
                    Modifier.fillMaxSize().background(ThemeSemantics.scrimBase),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            android.text.format.DateFormat.format(
                                if (com.carteltv.tv.data.preferences.AppPreferences.clock24) "HH:mm" else "h:mm a", now).toString(),
                            fontSize = ThemeSpec.Type.display, fontWeight = FontWeight.Thin, color = ThemeSemantics.accentBright.copy(alpha = 0.85f),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            android.text.format.DateFormat.format("EEEE · d MMMM", now).toString()
                                .uppercase(),
                            fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.accent, letterSpacing = 0.sp,
                        )
                        Spacer(Modifier.height(42.dp))
                        val n = NotificationMirror.items.size
                        if (n > 0) Text("Standby · $n Queued", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Spacer(Modifier.height(ThemeSpec.Space.sm))
                        Text("Standby", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, letterSpacing = 0.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        AppScope.io.launch { SleepTimerState.clear() }
    }
}

/** Sleep + media-stop timer buses. */
object SleepTimerState {
    /** epoch ms when the veil fires; 0 = disarmed */
    var sleepAtMs by androidx.compose.runtime.mutableLongStateOf(0L)

    /** epoch ms when playback should pause; 0 = disarmed */
    var mediaStopAtMs by androidx.compose.runtime.mutableLongStateOf(0L)

    fun armSleep(minutes: Int) {
        sleepAtMs = System.currentTimeMillis() + minutes * 60_000L
    }
    fun armMediaStop(minutes: Int) {
        mediaStopAtMs = System.currentTimeMillis() + minutes * 60_000L
    }
    fun clear() { sleepAtMs = 0 }
    fun clearMedia() { mediaStopAtMs = 0 }

    /** tick loop — call once from CartelApp.onCreate */
    fun start(ctx: android.content.Context) {
        AppScope.io.launch {
            while (true) {
                delay(5_000)
                val s = sleepAtMs
                // A movie playing holds the room awake: skip the veil while playback is
                // active, same contract as the media-stop timer pausing the player.
                if (s in 1..System.currentTimeMillis() && !NowPlayingState.isPlaying()) {
                    clear()
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(ctx, SleepTimerActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }
}
