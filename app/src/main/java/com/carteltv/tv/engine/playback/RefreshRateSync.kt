package com.carteltv.tv.engine.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Surface
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Matches the TV's refresh rate to the video's frame rate so 24 fps film does not judder on a 60 Hz panel.
 * Android 12+ asks the surface to change rate; Android 11 and earlier switch the display mode directly.
 */
object RefreshRateSync {

    data class Mode(val id: Int, val width: Int, val height: Int, val rate: Float)

    /**
     * Picks the same-resolution mode whose refresh rate is a whole multiple of [fps] (lowest multiple first,
     * so 24 fps prefers 24 Hz over 48 or 120). Returns null when the panel offers none, leaving the mode alone.
     */
    fun pickModeId(modes: List<Mode>, curWidth: Int, curHeight: Int, fps: Float): Int? {
        if (fps < 10f || fps > 120f) return null
        return modes
            .filter { it.width == curWidth && it.height == curHeight && it.rate > 0f }
            .mapNotNull { m ->
                val ratio = m.rate / fps
                val whole = ratio.roundToInt()
                if (whole >= 1 && abs(ratio - whole) <= 0.005f * whole) Triple(m, whole, abs(m.rate - fps * whole)) else null
            }
            .minWithOrNull(compareBy<Triple<Mode, Int, Float>>({ it.second }, { it.third }))
            ?.first?.id
    }

    private fun Context.activity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Requests [fps] for the playing video. Safe to call repeatedly; failures leave playback untouched. */
    fun apply(context: Context, surface: Surface?, fps: Float) {
        if (fps <= 0f) return
        val activity = context.activity() ?: return
        runCatching {
            if (surface != null && surface.isValid) {
                if (Build.VERSION.SDK_INT >= 31) {
                    surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
                    return
                }
                // Android 11 honours the vote only for seamless switches, which TV panels are not:
                // verified on SHIELD that 24p/50p kept running at 60 Hz on this vote alone. Fall through
                // to the window's preferredDisplayModeId, the switch that actually retunes the TV.
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            val cur = display.mode
            val id = pickModeId(
                display.supportedModes.map { Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) },
                cur.physicalWidth, cur.physicalHeight, fps,
            ) ?: return
            if (id == cur.modeId) return
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = id
            activity.window.attributes = lp
        }
    }

    /** Hands the display back to the system default once playback ends. */
    fun restore(context: Context) {
        val activity = context.activity() ?: return
        runCatching {
            val lp = activity.window.attributes
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                activity.window.attributes = lp
            }
        }
    }
}
