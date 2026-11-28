package com.carteltv.tv.engine.captions

import android.content.Context
import android.view.accessibility.CaptioningManager

/**
 * System caption defaults from CaptioningManager. Explicit in-app choices always win over these;
 * they only fill in where the viewer has not picked a language or caption size by hand.
 */
object SystemCaptions {

    data class Defaults(
        val enabled: Boolean = false,
        /** Language code of the system caption locale, or null when the system has none set. */
        val locale: String? = null,
        val fontScale: Float = 1f,
        val style: CaptioningManager.CaptionStyle? = null,
    )

    fun defaults(context: Context): Defaults {
        val cm = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager ?: return Defaults()
        return Defaults(
            enabled = cm.isEnabled,
            locale = cm.locale?.language,
            fontScale = cm.fontScale,
            style = runCatching { cm.userStyle }.getOrNull(),
        )
    }

    /**
     * The subtitle preference to start playback with: an explicit in-app language wins; with none set,
     * system captions enabled turn subtitles on in the system caption locale (the device's language
     * when the system names none); system captions disabled leave the app's own auto behaviour alone.
     */
    fun subtitlePref(explicit: String, defaults: Defaults, deviceLanguage: String): String =
        explicit.trim().ifEmpty {
            if (defaults.enabled) defaults.locale?.takeIf { it.isNotBlank() } ?: deviceLanguage else ""
        }
}
