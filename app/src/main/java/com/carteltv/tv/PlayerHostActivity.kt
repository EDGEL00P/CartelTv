package com.carteltv.tv

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.Player
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
        val tune = com.carteltv.tv.data.xtream.LiveSourceSelector.best(
            url = url,
            title = title,
            refId = refId,
            client = com.carteltv.tv.data.xtream.ProviderSession.current,
            pool = com.carteltv.tv.data.xtream.LiveChannels.cached(),
            maxQualityRank = com.carteltv.tv.data.xtream.LiveSourceSelector.maxQualityForDisplayHeight(display?.mode?.physicalHeight),
        )
        val alternates = com.carteltv.tv.engine.playback.AlternateSourceSelector.decode(intent.getStringExtra("alternates"))

        AppPreferences.addRecent("${tune.refId}|${tune.title}|${tune.url}")

        val pipAction: (() -> Unit)? = if (
            android.os.Build.VERSION.SDK_INT < 34 &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) ({ showPictureInPicture() }) else null
        setContent {
            CartelTheme {
                PlayerScreenContent(
                    streamUrl = tune.url,
                    title = tune.title,
                    refId = tune.refId,
                    alternates = alternates,
                    onOpenPictureInPicture = pipAction,
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

    /**
     * True while the player on screen is actively playing. The manifest's supportsPictureInPicture
     * is only ever honoured with a mode entered, so the home key hands such a run to the PiP window;
     * a paused, failed or finished stream stays a full-screen card instead.
     */
    fun shouldEnterPictureInPicture(): Boolean {
        val p = activePlayer() ?: return false
        return runCatching { p.playWhenReady && p.playbackState == Player.STATE_READY }.getOrDefault(false)
    }

    /** Explicit user action only. Newer TV multitasking rules are not applicable to media playback here. */
    fun showPictureInPicture(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 34 ||
            !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) ||
            !shouldEnterPictureInPicture()
        ) return false
        return runCatching { enterPictureInPictureMode(pictureInPictureParams()) }.getOrDefault(false)
    }

    /** The PiP window takes the video's own shape, clamped to the ratios Android accepts. */
    private fun pictureInPictureParams(): PictureInPictureParams =
        PictureInPictureParams.Builder().apply {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                setTitle(intent.getStringExtra("title").orEmpty().ifBlank { "CartelTV" })
                setSubtitle(if (intent.getStringExtra("refId")?.startsWith("live:") == true) "Live TV" else "Video")
            }
            val size = runCatching { activePlayer()?.videoSize }.getOrNull()
            pipAspect(size?.width ?: 0, size?.height ?: 0, size?.unappliedRotationDegrees ?: 0)
                ?.let { (w, h) -> runCatching { setAspectRatio(Rational(w, h)) } }
        }.build()

    /** The player surface registers its remote for as long as it is on screen; that is ours. */
    private fun activePlayer(): Player? = PlayerRemote.active?.player
}

/** The widest window Android will draw, and (reciprocally) the narrowest. */
private const val PIP_MAX_ASPECT = 2.39f
private val PIP_MIN_ASPECT = 1f / PIP_MAX_ASPECT

/**
 * The PiP window's aspect from the video's size, as a reduced numerator/denominator pair: a
 * rotation-odd size is swapped first, then clamped into the range Android accepts so a stray
 * size never invalidates the whole params. Null when there is no usable size — the caller then
 * leaves the ratio unset and the window keeps the activity's.
 */
internal fun pipAspect(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val (w, h) = if (rotationDegrees == 90 || rotationDegrees == 270) height to width else width to height
    val ratio = w.toFloat() / h
    return when {
        ratio > PIP_MAX_ASPECT -> 239 to 100
        ratio < PIP_MIN_ASPECT -> 100 to 239
        else -> {
            var a = w
            var b = h
            while (b != 0) { val t = a % b; a = b; b = t }
            w / a to h / a
        }
    }
}
