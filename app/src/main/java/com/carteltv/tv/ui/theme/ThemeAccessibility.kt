package com.carteltv.tv.ui.theme

import android.content.Context
import android.provider.Settings
import com.carteltv.tv.data.preferences.AppPreferences

/**
 * System accessibility defaults, applied once at startup. Call [install] from Application.onCreate
 * after AppPreferences.init — the persisted in-app choices stay primary; system settings only OR in.
 * Exposed as a function so the app class wires it with a single call.
 */
object ThemeAccessibility {

    fun install(context: Context) {
        applyDefaults(
            persistedReduceTransparency = AppPreferences.reduceTransparency,
            systemHighContrast = highTextContrastEnabled(context),
            systemAnimationsRemoved = animatorScaleDisabled(context),
        )
    }

    /**
     * Startup-only merge: system high text contrast ORs into the persisted reduce-transparency
     * choice (either alone makes glass solid), and the system animation switch turns shared motion off.
     */
    internal fun applyDefaults(
        persistedReduceTransparency: Boolean,
        systemHighContrast: Boolean,
        systemAnimationsRemoved: Boolean,
    ) {
        ThemePresets.reduceTransparency = persistedReduceTransparency || systemHighContrast
        ThemePresets.systemAnimationsRemoved = systemAnimationsRemoved
    }

    fun highTextContrastEnabled(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, "accessibility_high_text_contrast_enabled", 0) == 1
    }.getOrDefault(false)

    /** ANIMATOR_DURATION_SCALE == 0: the user asked for no motion system-wide. */
    fun animatorScaleDisabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
}
