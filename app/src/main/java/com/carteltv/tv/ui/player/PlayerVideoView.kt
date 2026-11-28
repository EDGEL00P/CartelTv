@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.player

import android.content.Context
import android.view.accessibility.CaptioningManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.carteltv.tv.data.preferences.AppPreferences

/**
 * Aspect ratio and subtitle size of the picture, kept per title. Both change what the surface
 * renders, so the host owns them: the deck only reads the current value and asks for the next one.
 * A host that plays short, unrelated titles (YouTube) passes one fixed key instead of a title's.
 */
@Stable
class VideoGeometry internal constructor(
    private val context: Context,
    private val refId: String,
) {
    private var tick by mutableIntStateOf(0)

    /** 0 Fit, 1 Cinema, 2 Zoom, 3 Stretch. Legacy saves may hold a removed mode (4); it folds back to Fit. */
    val aspectMode: Int get() = read { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("asp:$refId", 0) % 4 }

    /** Subtitle scale in percent: 100, 150, 200 or 75. */
    val subtitleScale: Int get() = read { AppPreferences.titleSubtitleScale(refId) }

    fun cycleAspect() {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("asp:$refId", (aspectMode + 1) % 4)
            .apply()
        tick++
    }

    fun cycleSubtitleScale() {
        val next =
            when (AppPreferences.titleSubtitleScale(refId)) {
                100 -> 150
                150 -> 200
                200 -> 75
                else -> 100
            }
        if (refId.isNotBlank()) AppPreferences.setTitleSubtitleScale(refId, next)
        tick++
    }

    // Reading the tick is what makes a change to it recompose whoever read the value.
    private fun <T> read(block: () -> T): T {
        tick
        return block()
    }

    private companion object {
        const val PREFS = "carteltv_player"
    }
}

@Composable
fun rememberVideoGeometry(refId: String): VideoGeometry {
    val context = LocalContext.current
    return remember(context, refId) { VideoGeometry(context, refId) }
}

/**
 * The video surface of a full-screen player: Media3's PlayerView with no controller of its own,
 * sized by [geometry]. [onView] receives the view once, for a host that needs the surface underneath.
 */
@Composable
internal fun PlayerVideoView(
    player: Player,
    geometry: VideoGeometry,
    modifier: Modifier = Modifier,
    onView: (PlayerView) -> Unit = {},
) {
    val aspectMode = geometry.aspectMode
    val subtitleScale = geometry.subtitleScale
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = false // the deck is the only UI
                setShowNextButton(false)
                setShowPreviousButton(false)
                controllerAutoShow = false
                isFocusable = false // the surface around it takes the remote
                // A SurfaceView inside a Compose AndroidView is stretched/cropped/leaked on API 34
                // (androidx/media#1237); this opts into the framework sync fix. No-op elsewhere, and a
                // no-op on API 35+ where the bug is still open upstream.
                setEnableComposeSurfaceSyncWorkaround(true)
                onView(this)
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.resizeMode =
                when (aspectMode) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                    // Cinema
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                    // Zoom (slight)
                    3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL

                    // Stretch
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            // Slight zoom for mode 2
            view.scaleX = if (aspectMode == 2) 1.08f else 1f
            view.scaleY = if (aspectMode == 2) 1.08f else 1f
            view.subtitleView?.let { subs ->
                // The user's system caption style is the default look; an in-app per-title scale
                // (anything other than the default 100) overrides the system font scale for size.
                val captions =
                    com.carteltv.tv.engine.captions.SystemCaptions
                        .defaults(view.context)
                captions.style?.let { subs.setStyle(CaptionStyleCompat.createFromCaptionStyle(it)) }
                val scale = if (subtitleScale != 100) subtitleScale / 100f else captions.fontScale
                subs.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
            }
        },
        modifier = modifier,
    )
}
