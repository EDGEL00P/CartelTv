package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault

private const val DEFAULT_UPDATE_FEED = "https://github.com/EDGEL00P/CartelTv/releases/latest/download/update.json"

/**
 * General / miscellaneous preferences: clock, updater feed, sleep timer, key remap,
 * profiles, status bar, launcher authority, stream badges, history.
 *
 * Extracted from [AppPreferences] — remaining cross-cutting settings have their own responsibility.
 */
class GeneralPreferences(private val sp: SharedPreferences) {

    /** Epoch-ms of the last completed search query (online results cache / invalidation). */
    var lastQuery: Long
        get() = sp.getLong("last_query_ms", 0L)
        set(v) = sp.edit().putLong("last_query_ms", v).apply()

    var clock24: Boolean
        get() = sp.getBoolean("clock24", false)
        set(v) = sp.edit().putBoolean("clock24", v).apply()

    /** clock size in sp */
    var clockSizeSp: Int
        get() = sp.getInt("clock_sz", 14)
        set(v) = sp.edit().putInt("clock_sz", v.coerceIn(8, 24)).apply()

    // epg-latest is published with --latest=false, so "latest" always resolves to the newest app release.
    var updaterFeedUrl: String
        get() = sp.getString("updater_feed", "").orEmpty().ifBlank { DEFAULT_UPDATE_FEED }
        set(v) = sp.edit().putString("updater_feed", v).apply()

    var focusSounds: Boolean
        get() = sp.getBoolean("focus_sounds", true)
        set(v) = sp.edit().putBoolean("focus_sounds", v).apply()

    /** last-chosen sleep minutes for one-press re-arm */
    var sleepMinutes: Int
        get() = sp.getInt("sleep_min", 45)
        set(v) = sp.edit().putInt("sleep_min", v.coerceIn(5, 240)).apply()

    /** notification popup overlay on Home */
    var noticePopups: Boolean
        get() = sp.getBoolean("notice_pop", true)
        set(v) = sp.edit().putBoolean("notice_pop", v).apply()

    /** "flavor:keycode=action" lines ── */
    fun remapRaw(): String = sp.getString("remap_map", "") ?: ""
    fun setRemapRaw(v: String) = sp.edit().putString("remap_map", v).apply()

    fun remapBindings(): List<com.carteltv.tv.data.remap.KeyRemap.Bind> =
        com.carteltv.tv.data.remap.KeyRemap.parse(remapRaw())

    fun addRemapBinding(b: com.carteltv.tv.data.remap.KeyRemap.Bind) {
        val cur = remapBindings().filterNot { it.flavor == b.flavor && it.keyCode == b.keyCode }
        setRemapRaw(com.carteltv.tv.data.remap.KeyRemap.serialize(cur + b))
    }

    fun clearRemapBinding(b: com.carteltv.tv.data.remap.KeyRemap.Bind) =
        setRemapRaw(com.carteltv.tv.data.remap.KeyRemap.serialize(
            remapBindings().filterNot { it == b }))

    var profilesJson: String
        get() = sp.getString("prof_json", "") ?: ""
        set(v) = sp.edit().putString("prof_json", v).apply()
    var activeProfileId: String
        get() = sp.getString("prof_active", "") ?: ""
        set(v) = sp.edit().putString("prof_active", v).apply()
    var usageJson: String
        get() = sp.getString("prof_usage", "") ?: ""
        set(v) = sp.edit().putString("prof_usage", v).apply()
    var askProfileOnBoot: Boolean
        get() = sp.getBoolean("prof_boot", true)
        set(v) = sp.edit().putBoolean("prof_boot", v).apply()

    /** bits: time,date,weather,profile,apps,media,network,bt,gear,alerts,sleep,sports,voice */
    var barClusters: String
        get() = sp.getString("bar_clusters", "1111111011111") ?: "1111111011111"
        set(v) = sp.edit().putString("bar_clusters", v).apply()

    /** settings_top_bar_position: "top" | "bottom" */
    var barPosition: String
        get() = sp.getString("bar_pos", "top") ?: "top"
        set(v) = sp.edit().putString("bar_pos", v).apply()

    /** settings_top_bar_hide_until_focused — focus-visibility, no timer */
    var barHideUntilFocused: Boolean
        get() = sp.getBoolean("bar_huf", false)
        set(v) = sp.edit().putBoolean("bar_huf", v).apply()

    /** user-ordered dock tabs ("HOME,LIVE,…" subset); empty = default order */
    var barTabs: String
        get() = sp.getString("bar_tabs", "") ?: ""
        set(v) = sp.edit().putString("bar_tabs", v).apply()

    /** Redirect stock TV launcher windows back to CartelTV via KeyRemapService. */
    var overrideHomeButton: Boolean
        get() = sp.getBoolean("override_home_button", true)
        set(v) = sp.edit().putBoolean("override_home_button", v).apply()

    /** Direct KEYCODE_HOME interception via FLAG_REQUEST_FILTER_KEY_EVENTS (zero flash). */
    var enhancedHomeDetection: Boolean
        get() = sp.getBoolean("better_home_detection", false)
        set(v) = sp.edit().putBoolean("better_home_detection", v).apply()

    /** Keep Back from exiting CartelTV at the root home surface. */
    var overrideBackButton: Boolean
        get() = sp.getBoolean("override_back_button", true)
        set(v) = sp.edit().putBoolean("override_back_button", v).apply()

    /** Relaunch CartelTV automatically on BOOT_COMPLETED / QUICKBOOT_POWERON. */
    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    /** Show Android TV system Watch Next rail on Home when programs exist. */
    var showWatchNext: Boolean
        get() = sp.getBoolean("show_watch_next", true)
        set(v) = sp.edit().putBoolean("show_watch_next", v).apply()

    /** Tracks whether initial Accessibility-enabled defaults were applied once. */
    var accessibilityDefaultsConfigured: Boolean
        get() = sp.getBoolean("accessibility_defaults_configured", false)
        set(v) = sp.edit().putBoolean("accessibility_defaults_configured", v).apply()

    /** Tracks whether the Android 13+ runtime POST_NOTIFICATIONS prompt was shown once. */
    var notifPromptDone: Boolean
        get() = sp.getBoolean("notif_prompt_done", false)
        set(v) = sp.edit().putBoolean("notif_prompt_done", v).apply()

    /** URL to fetch badge definitions from. */
    var streamBadgesUrl: String
        get() = sp.getString("stream_badges_url", "") ?: ""
        set(v) = sp.edit().putString("stream_badges_url", v.trim()).apply()

    /** Whether stream badges are displayed. */
    var streamBadgesOn: Boolean
        get() = sp.getBoolean("stream_badges_on", true)
        set(v) = sp.edit().putBoolean("stream_badges_on", v).apply()

    /** Epoch-ms of last badge fetch. */
    var streamBadgesFetched: Long
        get() = sp.getLong("stream_badges_fetched", 0L)
        set(v) = sp.edit().putLong("stream_badges_fetched", v).apply()

    var historyOn: Boolean
        get() = sp.getBoolean("history_on", true)
        set(v) = sp.edit().putBoolean("history_on", v).apply()

    /** scheduled DVR jobs, SecretVault-encrypted JSON list */
    var scheduledJobsRaw: String
        get() = sp.getString("sched_jobs", "") ?: ""
        set(v) = sp.edit().putString("sched_jobs", v).apply()

    /** DVR padding: minutes pulled in front of / added after every scheduled capture. */
    var recPadBeforeMin: Int
        get() = sp.getInt("rec_pad_before", 0).coerceIn(0, 30)
        set(v) = sp.edit().putInt("rec_pad_before", v.coerceIn(0, 30)).apply()

    var recPadAfterMin: Int
        get() = sp.getInt("rec_pad_after", 0).coerceIn(0, 30)
        set(v) = sp.edit().putInt("rec_pad_after", v.coerceIn(0, 30)).apply()

    /** Search torrent indexes from inside the app, alongside any Stremio addons. */
    var builtInSources: Boolean
        get() = sp.getBoolean("builtin_sources", true)
        set(v) = sp.edit().putBoolean("builtin_sources", v).apply()
}
