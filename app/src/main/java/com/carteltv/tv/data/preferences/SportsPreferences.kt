package com.carteltv.tv.data.preferences

import android.content.SharedPreferences

/**
 * Sports preferences: card size, layout, odds, zones, alerts, league picks.
 *
 * Extracted from [AppPreferences] — sports UI state has its own responsibility.
 */
class SportsPreferences(private val sp: SharedPreferences) {

    /** Sports game card size: "large" | "medium" | "small". */
    var sportsCardSize: String
        get() = sp.getString("sports_card_size", "large")?.takeIf { it in listOf("large", "medium", "small") } ?: "large"
        set(v) = sp.edit().putString("sports_card_size", v).apply()

    /** Sports games layout: "cards" | "guide". */
    var sportsLayout: String
        get() = sp.getString("sports_layout", "cards")?.takeIf { it in listOf("cards", "guide") } ?: "cards"
        set(v) = sp.edit().putString("sports_layout", v).apply()

    /** Show betting odds on Sports game cards. */
    var sportsShowOdds: Boolean
        get() = sp.getBoolean("sports_show_odds", false)
        set(v) = sp.edit().putBoolean("sports_show_odds", v).apply()

    /** Hide red zone on sports cards. */
    var sportsHideRedZone: Boolean
        get() = sp.getBoolean("sports_hide_red_zone", false)
        set(v) = sp.edit().putBoolean("sports_hide_red_zone", v).apply()

    /** Sports league rail shows league names (expanded). */
    var sportsRailExpanded: Boolean
        get() = sp.getBoolean("sports_rail_expanded", false)
        set(v) = sp.edit().putBoolean("sports_rail_expanded", v).apply()

    /** Auto-play reminded game on its channel when it starts. */
    var sportsAutoPlay: Boolean
        get() = sp.getBoolean("sports_auto_play", false)
        set(v) = sp.edit().putBoolean("sports_auto_play", v).apply()

    fun sportsPreferredCountries(): List<String> =
        sp.getString("sports_countries", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun setSportsPreferredCountries(countries: List<String>) {
        sp.edit().putString("sports_countries", countries.joinToString(",")).apply()
    }

    /** Hide leagues with no games in Sports. */
    var sportsHideEmptyLeagues: Boolean
        get() = sp.getBoolean("sports_hide_empty", true)
        set(v) = sp.edit().putBoolean("sports_hide_empty", v).apply()

    /** Favourite league ID in Sports. */
    var sportsFavoriteLeague: String
        get() = sp.getString("sports_fav_league", "") ?: ""
        set(v) = sp.edit().putString("sports_fav_league", v.trim()).apply()

    /** Which zone the sports screens use: "auto" (from the viewer's IP) or "device". */
    var sportsZoneMode: String
        get() = sp.getString("sports_zone_mode", "auto") ?: "auto"
        set(v) = sp.edit().putString("sports_zone_mode", v).apply()

    /** The zone the viewer picked from the world list; used when [sportsZoneMode] is "manual". */
    var sportsZoneManual: String
        get() = sp.getString("sports_zone_manual", "") ?: ""
        set(v) = sp.edit().putString("sports_zone_manual", v).apply()

    /** The zone the IP lookup last returned, and when; used when [sportsZoneMode] is "auto". */
    var ipZoneId: String
        get() = sp.getString("ip_zone_id", "") ?: ""
        set(v) = sp.edit().putString("ip_zone_id", v).apply()
    var ipZoneAtMs: Long
        get() = sp.getLong("ip_zone_at", 0L)
        set(v) = sp.edit().putLong("ip_zone_at", v).apply()

    /** Prefer quality over speed in sports channel matching. */
    var sportsPreferQuality: Boolean
        get() = sp.getBoolean("sports_prefer_quality", true)
        set(v) = sp.edit().putBoolean("sports_prefer_quality", v).apply()

    /** Goal alert delay in seconds (0 = none; useful for HLS lag). */
    var goalAlertDelay: Int
        get() = sp.getInt("goal_alert_delay", 0).coerceIn(0, 120)
        set(v) = sp.edit().putInt("goal_alert_delay", v.coerceIn(0, 120)).apply()

    /** User's game/team → channel picks as JSON. */
    var sportsChannelPicks: String
        get() = sp.getString("sports_channel_picks", "") ?: ""
        set(v) = sp.edit().putString("sports_channel_picks", v).apply()
}
