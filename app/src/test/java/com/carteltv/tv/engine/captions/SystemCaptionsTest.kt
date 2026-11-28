package com.carteltv.tv.engine.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemCaptionsTest {

    @Test
    fun explicit_in_app_language_wins_over_the_system_default() {
        val d = SystemCaptions.Defaults(enabled = true, locale = "es")
        assertEquals("ger", SystemCaptions.subtitlePref("ger", d, "en"))
        assertEquals("spa", SystemCaptions.subtitlePref(" spa ", d, "en"))
    }

    @Test
    fun system_captions_on_default_to_the_system_locale_then_the_device_language() {
        assertEquals("es", SystemCaptions.subtitlePref("", SystemCaptions.Defaults(enabled = true, locale = "es"), "en"))
        assertEquals("en", SystemCaptions.subtitlePref("", SystemCaptions.Defaults(enabled = true, locale = null), "en"))
        assertEquals("en", SystemCaptions.subtitlePref("", SystemCaptions.Defaults(enabled = true, locale = "  "), "en"))
    }

    @Test
    fun system_captions_off_leave_the_apps_auto_behaviour_untouched() {
        assertEquals("", SystemCaptions.subtitlePref("", SystemCaptions.Defaults(enabled = false, locale = "es"), "en"))
        assertEquals("", SystemCaptions.subtitlePref("", SystemCaptions.Defaults(), "en"))
    }
}
