package com.carteltv.tv.data.remap

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Pure decision logic for dual-mode Home interception.
 * Kept free of Android runtime side effects so every branch is unit-testable.
 */
object HomeOverride {

    /**
     * Exact Activity class names of stock Android TV / Google TV / Fire TV home screens.
     * Scoped to these 4 targets so random dialogs/settings windows never trigger a redirect.
     */
    val STOCK_HOME_ACTIVITIES: Set<String> = setOf(
        "com.google.android.tvlauncher.MainActivity",
        "com.google.android.leanbacklauncher.MainActivity",
        "com.google.android.apps.tv.launcherx.home.HomeActivity",
        "com.amazon.tv.launcher.ui.HomeActivity_vNext",
    )

    val STOCK_HOME_PACKAGES: Set<String> = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.google.android.apps.tv.launcherx",
        "com.amazon.tv.launcher",
    )

    /**
     * 0x34030000: NEW_TASK | SINGLE_TOP | CLEAR_TOP | REORDER_TO_FRONT | NO_ANIMATION.
     * NO_ANIMATION prevents a visual flash when bouncing off the stock launcher.
     */
    const val HOME_LAUNCH_FLAGS: Int =
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

    /**
     * 0x30020000: NEW_TASK | SINGLE_TOP | REORDER_TO_FRONT.
     */
    const val BOOT_LAUNCH_FLAGS: Int =
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT

    const val KEYCODE_HOME = 3
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    enum class KeyAction {
        PASS_THROUGH,
        CONSUME_ONLY,
        LAUNCH_AND_CONSUME,
    }

    data class ServiceConfig(
        val flags: Int,
        val eventTypes: Int,
    )

    /**
     * Decide whether an incoming [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] should
     * foreground CartelTV. Inactive when [enhancedHome] is enabled (key filtering owns HOME).
     */
    fun shouldRedirectWindowEvent(
        overrideHome: Boolean,
        enhancedHome: Boolean,
        eventType: Int,
        className: String?,
        nowMs: Long,
        lastLaunchMs: Long,
    ): Boolean {
        if (enhancedHome || !overrideHome) return false
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        if (className == null || className !in STOCK_HOME_ACTIVITIES) return false
        return nowMs - lastLaunchMs > 0L
    }

    /**
     * Decide how [KeyRemapService.onKeyEvent] handles a key event when enhanced home detection is on.
     * Swallows BOTH ACTION_DOWN and ACTION_UP for KEYCODE_HOME, launching on ACTION_UP.
     */
    fun handleHomeKeyEvent(
        enhancedHome: Boolean,
        keyCode: Int,
        action: Int,
    ): KeyAction {
        if (!enhancedHome || keyCode != KEYCODE_HOME) return KeyAction.PASS_THROUGH
        return if (action == ACTION_UP) KeyAction.LAUNCH_AND_CONSUME else KeyAction.CONSUME_ONLY
    }

    /**
     * Compute updated [AccessibilityServiceInfo] flags and eventTypes.
     * Preserves FLAG_REQUEST_FILTER_KEY_EVENTS whenever either enhanced home detection OR
     * user key-remap bindings are active, and disables window event delivery when not needed.
     */
    fun computeServiceConfig(
        currentFlags: Int,
        overrideHome: Boolean,
        enhancedHome: Boolean,
        hasCustomKeyBindings: Boolean,
    ): ServiceConfig {
        val needKeyFilter = enhancedHome || hasCustomKeyBindings
        val flags = if (needKeyFilter) {
            currentFlags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            currentFlags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        val eventTypes = if (overrideHome && !enhancedHome) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        } else {
            0
        }
        return ServiceConfig(flags = flags, eventTypes = eventTypes)
    }

    /**
     * Standby-wake guard: when Google TV wakes from a long standby it
     * often resurrects its own stock launcher home screen. Re-assert CartelTV only inside the
     * 20-second post-wake window so mid-playback Wi-Fi reconnects never take focus.
     */
    const val POST_WAKE_WINDOW_MS: Long = 20_000L

    fun shouldRedirectPostWake(
        overrideHome: Boolean,
        startOnBoot: Boolean,
        packageName: String?,
        className: String?,
        nowUptimeMs: Long,
        screenOnUptimeMs: Long,
    ): Boolean {
        if (!overrideHome || !startOnBoot) return false
        if (screenOnUptimeMs <= 0L || nowUptimeMs - screenOnUptimeMs > POST_WAKE_WINDOW_MS) return false
        if (packageName == null || packageName !in STOCK_HOME_PACKAGES) return false
        val cls = className.orEmpty()
        return cls in STOCK_HOME_ACTIVITIES ||
            cls.contains("HomeActivity") ||
            cls.contains("MainActivity") ||
            cls.contains("LauncherActivity")
    }
}
