package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault

/**
 * YouTube (CartelTube mods) preferences.
 *
 * Extracted from [AppPreferences] — YouTube client mods have their own responsibility.
 */
class YouTubeModsPreferences(private val sp: SharedPreferences) {

    var youTubeSponsorBlock: Boolean
        get() = sp.getBoolean("yt_sb", true)
        set(v) = sp.edit().putBoolean("yt_sb", v).apply()

    var youTubeCategoryPreset: Int
        get() = sp.getInt("yt_catpreset", 0)
        set(v) = sp.edit().putInt("yt_catpreset", v).apply()

    var youTubeMinSeconds: Int
        get() = sp.getInt("yt_min_secs", 0)
        set(v) = sp.edit().putInt("yt_min_secs", v).apply()

    var youTubeHideWatched: Boolean
        get() = sp.getBoolean("yt_hide_watched", false)
        set(v) = sp.edit().putBoolean("yt_hide_watched", v).apply()

    var youTubeBlockWords: String
        get() = sp.getString("yt_block_words", "") ?: ""
        set(v) = sp.edit().putString("yt_block_words", v).apply()

    var youTubeBlockChannels: String
        get() = sp.getString("yt_block_channels", "") ?: ""
        set(v) = sp.edit().putString("yt_block_channels", v).apply()

    var youTubeDislikes: Boolean
        get() = sp.getBoolean("yt_dislikes", true)
        set(v) = sp.edit().putBoolean("yt_dislikes", v).apply()

    var youTubeCodec: Int
        get() = sp.getInt("yt_codec", 0)
        set(v) = sp.edit().putInt("yt_codec", v).apply()

    var youTubeSpeed: Float
        get() = sp.getFloat("yt_speed", 1f)
        set(v) = sp.edit().putFloat("yt_speed", v).apply()

    var youTubeQuality: Int
        get() = sp.getInt("yt_quality", 0)   // 0=up to 8K · 1=720p · 2=muxed 360p
        set(v) = sp.edit().putInt("yt_quality", v).apply()

    var youTubeRefreshToken: String
        get() = SecretVault.decrypt(sp.getString("yt_refresh", null)) ?: ""
        set(v) = sp.edit().putString("yt_refresh", SecretVault.encrypt(v.trim())).apply()

    /** Epoch-ms of the newest YouTube subscription video the user has seen. */
    var youTubeSubSeenMs: Long
        get() = sp.getLong("yt_sub_seen_ms", 0L)
        set(v) = sp.edit().putLong("yt_sub_seen_ms", v).apply()

    var youTubeHistoryRaw: String
        get() = sp.getString("yt_history_raw", "") ?: ""
        set(v) = sp.edit().putString("yt_history_raw", v).apply()

    var youTubeSavedRaw: String
        get() = sp.getString("yt_saved_raw", "") ?: ""
        set(v) = sp.edit().putString("yt_saved_raw", v).apply()

    var youTubeSearchesRaw: String
        get() = sp.getString("yt_searches_raw", "") ?: ""
        set(v) = sp.edit().putString("yt_searches_raw", v).apply()

    var youTubeSubsRaw: String
        get() = sp.getString("yt_subs_raw", "") ?: ""
        set(v) = sp.edit().putString("yt_subs_raw", v).apply()
}
