package com.carteltv.tv.data.remap

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.carteltv.tv.MainActivity
import com.carteltv.tv.data.watch.WatchNextItem
import com.carteltv.tv.data.watch.WatchNextRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for dual-mode Home interception, service flags, and Watch Next ranking. */
class HomeInterceptTest {

    @Test
    fun home_and_boot_launch_flag_bitmasks_match_expected_contracts() {
        assertEquals(0x34030000, HomeOverride.HOME_LAUNCH_FLAGS)
        assertEquals(0x30020000, HomeOverride.BOOT_LAUNCH_FLAGS)
    }

    @Test
    fun window_event_redirects_only_exact_stock_home_activities() {
        for (cls in HomeOverride.STOCK_HOME_ACTIVITIES) {
            assertTrue(
                "Expected redirect for $cls",
                HomeOverride.shouldRedirectWindowEvent(
                    overrideHome = true,
                    enhancedHome = false,
                    eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    className = cls,
                    nowMs = 1_000L,
                    lastLaunchMs = 0L,
                ),
            )
        }
        // Unrelated window must never redirect.
        assertFalse(
            HomeOverride.shouldRedirectWindowEvent(
                overrideHome = true,
                enhancedHome = false,
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "com.android.tv.settings.MainSettings",
                nowMs = 1_000L,
                lastLaunchMs = 0L,
            ),
        )
        // Disabled override must not redirect.
        assertFalse(
            HomeOverride.shouldRedirectWindowEvent(
                overrideHome = false,
                enhancedHome = false,
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "com.google.android.tvlauncher.MainActivity",
                nowMs = 1_000L,
                lastLaunchMs = 0L,
            ),
        )
        // Enhanced home mode delegates to onKeyEvent instead of window events.
        assertFalse(
            HomeOverride.shouldRedirectWindowEvent(
                overrideHome = true,
                enhancedHome = true,
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "com.google.android.tvlauncher.MainActivity",
                nowMs = 1_000L,
                lastLaunchMs = 0L,
            ),
        )
        // Same-millisecond duplicate event is debounced.
        assertFalse(
            HomeOverride.shouldRedirectWindowEvent(
                overrideHome = true,
                enhancedHome = false,
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                className = "com.google.android.tvlauncher.MainActivity",
                nowMs = 1_000L,
                lastLaunchMs = 1_000L,
            ),
        )
    }

    @Test
    fun enhanced_home_key_handling_consumes_down_and_launches_on_up() {
        assertEquals(
            HomeOverride.KeyAction.CONSUME_ONLY,
            HomeOverride.handleHomeKeyEvent(
                enhancedHome = true,
                keyCode = HomeOverride.KEYCODE_HOME,
                action = HomeOverride.ACTION_DOWN,
            ),
        )
        assertEquals(
            HomeOverride.KeyAction.LAUNCH_AND_CONSUME,
            HomeOverride.handleHomeKeyEvent(
                enhancedHome = true,
                keyCode = HomeOverride.KEYCODE_HOME,
                action = HomeOverride.ACTION_UP,
            ),
        )
        // Non-HOME keys and disabled mode pass straight through.
        assertEquals(
            HomeOverride.KeyAction.PASS_THROUGH,
            HomeOverride.handleHomeKeyEvent(enhancedHome = true, keyCode = 4, action = HomeOverride.ACTION_UP),
        )
        assertEquals(
            HomeOverride.KeyAction.PASS_THROUGH,
            HomeOverride.handleHomeKeyEvent(
                enhancedHome = false,
                keyCode = HomeOverride.KEYCODE_HOME,
                action = HomeOverride.ACTION_UP,
            ),
        )
    }

    @Test
    fun service_config_preserves_key_filter_for_enhanced_home_or_custom_bindings() {
        val keyFlag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS

        // Standard window-override without key bindings: window events ON, key filter OFF.
        val windowOnly = HomeOverride.computeServiceConfig(
            currentFlags = keyFlag,
            overrideHome = true,
            enhancedHome = false,
            hasCustomKeyBindings = false,
        )
        assertEquals(0, windowOnly.flags and keyFlag)
        assertEquals(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, windowOnly.eventTypes)

        // Standard window-override WITH custom key remaps: both window events and key filter ON.
        val windowPlusRemap = HomeOverride.computeServiceConfig(
            currentFlags = 0,
            overrideHome = true,
            enhancedHome = false,
            hasCustomKeyBindings = true,
        )
        assertEquals(keyFlag, windowPlusRemap.flags and keyFlag)
        assertEquals(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, windowPlusRemap.eventTypes)

        // Enhanced home mode: key filter ON, window events OFF (zero IPC churn).
        val enhanced = HomeOverride.computeServiceConfig(
            currentFlags = 0,
            overrideHome = true,
            enhancedHome = true,
            hasCustomKeyBindings = false,
        )
        assertEquals(keyFlag, enhanced.flags and keyFlag)
        assertEquals(0, enhanced.eventTypes)
    }

    @Test
    fun watch_next_ranking_orders_by_type_then_recency_and_deduplicates() {
        val items = listOf(
            WatchNextItem("Old Watchlist", null, "app.a", null, "intent://1", 0, 0, watchNextType = 3, lastEngagementMs = 500),
            WatchNextItem("Continue Older", null, "app.b", null, "intent://2", 100, 1000, watchNextType = 0, lastEngagementMs = 200),
            WatchNextItem("Continue Newer", null, "app.b", null, "intent://3", 500, 1000, watchNextType = 0, lastEngagementMs = 900),
            WatchNextItem("Continue Newer", null, "app.b", null, "intent://3", 500, 1000, watchNextType = 0, lastEngagementMs = 800),
            WatchNextItem("Next Ep", null, "app.c", null, "intent://4", 0, 1000, watchNextType = 1, lastEngagementMs = 950),
        )
        val ranked = WatchNextRepository.rankPrograms(items)
        assertEquals(listOf("Continue Newer", "Continue Older", "Next Ep", "Old Watchlist"), ranked.map { it.title })
        assertEquals(0.5f, ranked.first().progress, 0.001f)
    }
}
