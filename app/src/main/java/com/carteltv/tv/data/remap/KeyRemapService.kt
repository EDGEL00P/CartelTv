package com.carteltv.tv.data.remap

import com.carteltv.tv.ui.components.TransientStatus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.system.SleepTimerState
import com.carteltv.tv.system.SleepTimerActivity
import com.carteltv.tv.nav.SectionNav
import com.carteltv.tv.nav.AppSection

/**
 * REMAP SINK + DUAL-MODE HOME AUTHORITY:
 * An accessibility service combining CartelTV's system-wide key remapper with:
 * zero-ADB dual-mode Home interception:
 *  - Mode A (default, [AppPreferences.overrideHomeButton]): watches TYPE_WINDOW_STATE_CHANGED on the
 *    4 stock TV launcher packages and immediately foregrounds CartelTV with NO_ANIMATION.
 *  - Mode B ([AppPreferences.enhancedHomeDetection]): swallows KEYCODE_HOME directly in [onKeyEvent]
 *    so the stock launcher never flashes a frame.
 *  - Reconfigures live without service restart via [ACTION_PREFS_CHANGED].
 */
class KeyRemapService : AccessibilityService() {

    companion object {
        @Volatile var instance: KeyRemapService? = null
            private set
        const val HOLD_MS = 800L
        const val ACTION_PREFS_CHANGED = "com.carteltv.tv.REMAP_PREFS_CHANGED"

        fun componentName(ctx: Context): ComponentName =
            ComponentName(ctx, KeyRemapService::class.java)

        /** Check whether [KeyRemapService] is currently enabled in Android's secure settings. */
        fun isEnabled(ctx: Context): Boolean {
            val full = "${ctx.packageName}/${KeyRemapService::class.java.name}"
            val short = componentName(ctx).flattenToShortString()
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { entry ->
                entry.equals(full, ignoreCase = true) || entry.equals(short, ignoreCase = true)
            }
        }

        /** Push live preference updates to a running [KeyRemapService] instance. */
        fun notifyPrefsChanged(ctx: Context) {
            runCatching {
                ctx.sendBroadcast(Intent(ACTION_PREFS_CHANGED).setPackage(ctx.packageName))
            }
        }

        /** Open Android Accessibility Settings directly (no ADB required), falling back to Settings. */
        fun openAccessibilitySettings(ctx: Context) {
            val primary = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallback = Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(primary) }
                .onFailure { runCatching { ctx.startActivity(fallback) } }
        }
    }

    private val downAt = HashMap<Int, Long>()
    private val plainFired = HashSet<Int>()

    @Volatile private var overrideHome = true
    @Volatile private var enhancedHome = false
    @Volatile private var lastHomeLaunchMs = 0L
    @Volatile private var screenOnUptimeMs = 0L
    private var homeLaunchIntent: Intent? = null
    private var receiverRegistered = false
    private var screenReceiverRegistered = false

    private val prefsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadConfig()
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                screenOnUptimeMs = android.os.SystemClock.uptimeMillis()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppPreferences.init(applicationContext)
        homeLaunchIntent = com.carteltv.tv.nav.AppLauncher.mainIntent(this).apply {
            addFlags(HomeOverride.HOME_LAUNCH_FLAGS)
        }
        reloadConfig()
        if (!receiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    prefsReceiver,
                    IntentFilter(ACTION_PREFS_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
            }
        }
        if (!screenReceiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    screenOnReceiver,
                    IntentFilter(Intent.ACTION_SCREEN_ON),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                screenReceiverRegistered = true
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(prefsReceiver) }
            receiverRegistered = false
        }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenOnReceiver) }
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    /** Re-read preferences and dynamically adjust [AccessibilityServiceInfo] flags/eventTypes. */
    fun reloadConfig() {
        AppPreferences.init(applicationContext)
        overrideHome = AppPreferences.overrideHomeButton
        enhancedHome = AppPreferences.enhancedHomeDetection
        val hasBinds = AppPreferences.remapBindings().isNotEmpty()

        val info = serviceInfo ?: AccessibilityServiceInfo()
        val computed = HomeOverride.computeServiceConfig(
            currentFlags = info.flags,
            overrideHome = overrideHome,
            enhancedHome = enhancedHome,
            hasCustomKeyBindings = hasBinds,
        )
        info.flags = computed.flags
        info.eventTypes = computed.eventTypes
        runCatching { setServiceInfo(info) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                com.carteltv.tv.data.apps.AppLaunchHistory.onForeground(this, pkg) { p ->
                    com.carteltv.tv.data.apps.InstalledAppsCache.apps.any { it.packageName == p }
                }
            }
        }
        val now = System.currentTimeMillis()
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        val postWake = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            HomeOverride.shouldRedirectPostWake(
                overrideHome = overrideHome,
                startOnBoot = AppPreferences.startOnBoot,
                packageName = pkg,
                className = cls,
                nowUptimeMs = android.os.SystemClock.uptimeMillis(),
                screenOnUptimeMs = screenOnUptimeMs,
            )
        if (!postWake && !HomeOverride.shouldRedirectWindowEvent(
                overrideHome = overrideHome,
                enhancedHome = enhancedHome,
                eventType = event.eventType,
                className = cls,
                nowMs = now,
                lastLaunchMs = lastHomeLaunchMs,
            )
        ) {
            return
        }
        lastHomeLaunchMs = now
        launchHome()
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Enhanced home detection (mode B): swallow KEYCODE_HOME directly before stock launcher renders.
        when (HomeOverride.handleHomeKeyEvent(enhancedHome, code, event.action)) {
            HomeOverride.KeyAction.LAUNCH_AND_CONSUME -> {
                launchHome()
                return true
            }
            HomeOverride.KeyAction.CONSUME_ONLY -> return true
            HomeOverride.KeyAction.PASS_THROUGH -> Unit
        }

        if (code in KeyRemap.RESERVED) return false
        val binds = AppPreferences.remapBindings()
        val plain = binds.firstOrNull { it.flavor == KeyRemap.Flavor.PLAIN && it.keyCode == code }
        val long = binds.firstOrNull { it.flavor == KeyRemap.Flavor.LONG && it.keyCode == code }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (plain == null && long == null) return false
                // Ignore key repeats so holding a button never resets downAt[code] or re-fires plain actions.
                if (event.repeatCount > 0) return true
                downAt[code] = android.os.SystemClock.elapsedRealtime()
                if (plain != null && long == null) {
                    plainFired += code
                    perform(plain.action)
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                val down = downAt.remove(code)
                if (plainFired.remove(code)) return true   // DOWN already acted
                if (down == null) return false
                val held = android.os.SystemClock.elapsedRealtime() - down
                when {
                    long != null && held >= HOLD_MS -> {
                        perform(long.action)
                        true
                    }
                    plain != null -> {
                        perform(plain.action)
                        true
                    }
                    long != null -> true
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun launchHome() {
        val intent = homeLaunchIntent ?: com.carteltv.tv.nav.AppLauncher.mainIntent(this).apply {
            addFlags(HomeOverride.HOME_LAUNCH_FLAGS)
        }.also { homeLaunchIntent = it }
        runCatching { startActivity(intent) }
    }

    /** Fire a mapped action; every path gives toast feedback. */
    private fun perform(action: String) {
        val say: (String) -> Unit = { msg ->
            TransientStatus.show(msg)
        }
        when (action) {
            "none" -> {}
            "dock" -> { com.carteltv.tv.nav.SectionNav.go(com.carteltv.tv.nav.AppSection.HOME) }
            "home" -> launchHome()
            "live", "movies", "series", "scores", "weather", "addons",
            "youtube", "search", "settings" -> {
                runCatching {
                    startActivity(com.carteltv.tv.nav.AppLauncher.mainIntent(this, action))
                }
            }
            "notifications_shade" -> runGlobal { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
            "recents" -> {
                runCatching {
                    startActivity(com.carteltv.tv.nav.AppLauncher.mainIntent(this, "recents"))
                }.onFailure { runGlobal { performGlobalAction(GLOBAL_ACTION_RECENTS) } }
            }
            "screenshot" -> {
                runGlobal { performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }
                say("Screen Captured")
            }
            "lock_screen" -> { runGlobal { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }; say("Standby Engaged") }
            "power_menu" -> runGlobal { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) }
            "sleep_timer" -> {
                SleepTimerState.armSleep(AppPreferences.sleepMinutes)
                say("Standby Timer Armed")
            }
            "night_veil" -> runCatching {
                startActivity(
                    Intent(this, com.carteltv.tv.system.SleepTimerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            "media_stop" -> { SleepTimerState.armMediaStop(30); say("Auto-Pause · 30 Min") }
            "system_settings" -> runCatching {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private inline fun runGlobal(block: () -> Unit) { runCatching(block) }
}
