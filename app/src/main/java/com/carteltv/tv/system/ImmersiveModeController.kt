package com.carteltv.tv.system

import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * ImmersionMarshal — one law for every activity in the court:
 * lean-back sticky immersion. Invoked from CartelApp's
 * activity-lifecycle callbacks, so all seven hosts (and any
 * future screen) obey without per-activity boilerplate.
 *
 * Why: the fullscreen theme only hides the bar at inflation;
 * dialogs, overscroll top-glows and dream exits re-summon it,
 * and on pre-14 TVs it then STAYS. Re-asserting at onResume
 * pins it. Behavior flags are also set defensively so the
 * bar cannot linger during transition frames.
 */
object ImmersiveModeController : android.app.Application.ActivityLifecycleCallbacks {

    /** Visible-activity count — LiveScoreMonitor silences score alerts while > 0. */
    @Volatile var anyVisible: Boolean = false; private set
    private var visibleCount = 0

    @Suppress("DEPRECATION")
    override fun onActivityResumed(activity: android.app.Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)

        // Sticky immersive: swipe/summons collapse again automatically
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())

        // Defensive belt for TV builds whose decor defers controller attach
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LOW_PROFILE or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    /* no-op observers — the law only needs resume (plus the visibility
       counter LiveScoreMonitor uses for its "quiet while app is open" law) */
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: android.app.Activity) {
        synchronized(this) { visibleCount++; anyVisible = true }
    }
    override fun onActivityPaused(activity: android.app.Activity) {}
    override fun onActivityStopped(activity: android.app.Activity) {
        synchronized(this) {
            if (visibleCount > 0) visibleCount--
            anyVisible = visibleCount > 0
        }
    }
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: android.app.Activity) {}
}
