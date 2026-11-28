package com.carteltv.tv.data.preferences

import android.content.SharedPreferences
import com.carteltv.tv.engine.stability.SecretVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Player / video surface preferences: surface mode, refresh sync, tunneling, audio offload,
 * video enhance, low-latency live, DV native, bar autohide, player clock, recent live,
 * guide layout/preview, spoiler shield, active leagues.
 *
 * Extracted from [AppPreferences] — playback engine settings have their own responsibility.
 */
class PlayerPreferences(private val sp: SharedPreferences) {

    /** Green-flicker fix: video surface as media overlay (Shield). */
    var videoMediaOverlay: Boolean
        get() = sp.getBoolean("video_media_overlay", false)
        set(v) = sp.edit().putBoolean("video_media_overlay", v).apply()

    /** Native video surface (beta): SurfaceView behind transparent Flutter view. */
    var nativeVideoSurface: Boolean
        get() = sp.getBoolean("native_video_surface", true)
        set(v) = sp.edit().putBoolean("native_video_surface", v).apply()

    /** Compatibility video: draws into texture instead of native view (Android 7-). */
    var compatVideo: Boolean
        get() = sp.getBoolean("compat_video", false)
        set(v) = sp.edit().putBoolean("compat_video", v).apply()

    /** Auto-play trailers while browsing movie/show details. */
    var autoTrailers: Boolean
        get() = sp.getBoolean("auto_trailers", true)
        set(v) = sp.edit().putBoolean("auto_trailers", v).apply()

    /** Reminder and goal-alert popups without their fill (outline only). */
    var transparentPopups: Boolean
        get() = sp.getBoolean("transparent_popups", false)
        set(v) = sp.edit().putBoolean("transparent_popups", v).apply()

    /** One-switch TiviMate-like plain IPTV player. */
    var minimalist: Boolean
        get() = sp.getBoolean("minimalist", false)
        set(v) = sp.edit().putBoolean("minimalist", v).apply()

    /** JSON snapshot of settings before minimalist was enabled (for revert). */
    var minimalistBefore: String
        get() = sp.getString("minimalist_before", "") ?: ""
        set(v) = sp.edit().putString("minimalist_before", v).apply()

    /** Version string the user chose to skip. */
    var skippedUpdateVersion: String
        get() = sp.getString("skipped_update", "") ?: ""
        set(v) = sp.edit().putString("skipped_update", v.trim()).apply()

    /** YYYY-MM-DD of last "subscription ends soon" card display. */
    var providerExpiryNoticeDay: String
        get() = sp.getString("provider_expiry_day", "") ?: ""
        set(v) = sp.edit().putString("provider_expiry_day", v).apply()

    /** Channel IDs confirmed to play at 2160p. */
    fun real4kChannels(): MutableSet<String> =
        sp.getStringSet("real_4k_channels", null)?.toMutableSet() ?: mutableSetOf()

    fun setReal4kChannels(ids: Set<String>) {
        sp.edit().putStringSet("real_4k_channels", ids).apply()
    }

    fun isReal4k(id: String): Boolean = id in real4kChannels()

    private val catalogSortJson get() = sp.getString("catalog_sort", "") ?: ""
    private data class SortCache(val raw: String, val map: Map<String, String>)
    @Volatile private var sortCache = SortCache("", emptyMap())

    private fun catalogSortMap(): Map<String, String> {
        val raw = catalogSortJson
        val cur = sortCache
        if (cur.raw == raw) return cur.map
        val parsed = runCatching { Json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
        sortCache = SortCache(raw, parsed)
        return parsed
    }

    fun catalogSort(category: String): String = catalogSortMap()[category] ?: ""

    fun setCatalogSort(category: String, sort: String) {
        val map = catalogSortMap().toMutableMap()
        map[category] = sort
        sp.edit().putString("catalog_sort", Json.encodeToString(map)).apply()
        sortCache = SortCache("", emptyMap())
    }

    /** Auto-select largest file in Media Hub source picker. */
    var autoLargest: Boolean
        get() = sp.getBoolean("auto_largest", false)
        set(v) = sp.edit().putBoolean("auto_largest", v).apply()

    /** Per-source preferences as JSON. */
    var archiveSourcePreferences: String
        get() = sp.getString("archive_source_prefs", "") ?: ""
        set(v) = sp.edit().putString("archive_source_prefs", v).apply()

    /** "playlistId:seriesId" → JSON of {after, next} episode data. */
    var nextEpisodesJson: String
        get() = sp.getString("next_episodes", "") ?: ""
        set(v) = sp.edit().putString("next_episodes", v).apply()

    /** Source name for Dispatcharr player. */
    var dispatcharrSources: String
        get() = SecretVault.decrypt(sp.getString("dispatcharr_sources", null)) ?: ""
        set(v) = sp.edit().putString("dispatcharr_sources", SecretVault.encrypt(v.trim())).apply()

    /** Web UI address, username and token as JSON. */
    var tuliprox: String
        get() = SecretVault.decrypt(sp.getString("tuliprox", null)) ?: ""
        set(v) = sp.edit().putString("tuliprox", SecretVault.encrypt(v.trim())).apply()

    /** Custom poster URL pattern for Media Hub. */
    var posterPattern: String
        get() = sp.getString("poster_pattern", "") ?: ""
        set(v) = sp.edit().putString("poster_pattern", v.trim()).apply()

    var barAutoHide: Boolean
        get() = sp.getBoolean("bar_autohide", true)
        set(v) = sp.edit().putBoolean("bar_autohide", v).apply()

    var playerClock: Boolean
        get() = sp.getBoolean("player_clock", false)
        set(v) = sp.edit().putBoolean("player_clock", v).apply()

    /** Most recently tuned live channels, newest first (stream ids). */
    fun recentLive(): List<Long> =
        sp.getString("recent_live", null)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    fun noteRecentLive(streamId: Long) {
        val next = (listOf(streamId) + recentLive().filter { it != streamId }).take(24)
        sp.edit().putString("recent_live", next.joinToString(",")).apply()
    }

    /** Guide rows use the tighter layout. */
    var guideCompact: Boolean
        get() = sp.getBoolean("guide_compact", false)
        set(v) = sp.edit().putBoolean("guide_compact", v).apply()

    /** The guide plays a muted preview of the focused channel; it costs one extra connection to the provider. */
    var guidePreview: Boolean
        get() = sp.getBoolean("guide_preview", true)
        set(v) = sp.edit().putBoolean("guide_preview", v).apply()

    /** Leagues shown in Sports, as a CSV of labels. Blank = the built-in set. */
    var activeLeagues: String
        get() = sp.getString("active_leagues", "") ?: ""
        set(v) = sp.edit().putString("active_leagues", v).apply()

    /** Blur scores in Sports and hold score alerts. */
    var spoilerShield: Boolean
        get() = sp.getBoolean("spoiler_shield", false)
        set(v) = sp.edit().putBoolean("spoiler_shield", v).apply()

    /** Switch the TV's refresh rate to the video's frame rate during playback. */
    var refreshSync: Boolean
        get() = sp.getBoolean("refresh_sync", false)
        set(v) = sp.edit().putBoolean("refresh_sync", v).apply()

    /** Hand video decode and release timing to the TV's tunneled path. Conflicts with [videoEnhance], which wins when both are set. */
    var tunneling: Boolean
        get() = sp.getBoolean("tunneling", true)
        set(v) = sp.edit().putBoolean("tunneling", v).apply()

    /** Let compatible compressed audio decode on the DSP to save power. */
    var audioOffload: Boolean
        get() = sp.getBoolean("audio_offload", true)
        set(v) = sp.edit().putBoolean("audio_offload", v).apply()

    /** GL post-processing on every frame (deband + sharpen) to smooth over low-bitrate rips. */
    var videoEnhance: Boolean
        get() = sp.getBoolean("video_enhance", false)
        set(v) = sp.edit().putBoolean("video_enhance", v).apply()

    /** Ask live-TV video decoders for low-latency output (Android 11). */
    var lowLatencyLive: Boolean
        get() = sp.getBoolean("low_latency_live", true)
        set(v) = sp.edit().putBoolean("low_latency_live", v).apply()

    /** Let a box's own Dolby Vision Profile 7 decoder play DV7 instead of converting it. */
    var dvNativeProfile7: Boolean
        get() = sp.getBoolean("dv_native_p7", true)
        set(v) = sp.edit().putBoolean("dv_native_p7", v).apply()

    /** One tuning in a multi-view grid; null = empty cell. */
    @Serializable
    data class MultiViewSlot(val refId: String, val title: String, val url: String)

    /** A saved arrangement of up to four tunings, kept per provider like group configs. */
    @Serializable
    data class MultiViewLayout(val name: String, val slots: List<MultiViewSlot?>)

    private val multiViewLayoutsJson get() = Json { ignoreUnknownKeys = true }

    /** Saved layouts for the active provider, oldest first. */
    fun multiViewLayouts(providerId: String): List<MultiViewLayout> {
        val all = decodeMultiViewLayouts(sp.getString("multiview_layouts", "") ?: "")
        return all[providerId] ?: emptyList()
    }

    fun saveMultiViewLayout(providerId: String, layout: MultiViewLayout) {
        val all = decodeMultiViewLayouts(sp.getString("multiview_layouts", "") ?: "").toMutableMap()
        val cur = all[providerId]?.orEmpty()?.toMutableList() ?: mutableListOf()
        cur.removeAll { it.name == layout.name }
        cur.add(layout)
        all[providerId] = cur
        sp.edit().putString("multiview_layouts", multiViewLayoutsJson.encodeToString(all)).apply()
    }

    fun deleteMultiViewLayout(providerId: String, name: String) {
        val all = decodeMultiViewLayouts(sp.getString("multiview_layouts", "") ?: "").toMutableMap()
        val cur = all[providerId]?.filter { it.name != name } ?: return
        if (cur.isEmpty()) all.remove(providerId) else all[providerId] = cur
        sp.edit().putString("multiview_layouts", multiViewLayoutsJson.encodeToString(all)).apply()
    }

    private fun decodeMultiViewLayouts(raw: String): Map<String, List<MultiViewLayout>> =
        runCatching { multiViewLayoutsJson.decodeFromString<Map<String, List<MultiViewLayout>>>(raw) }
            .getOrDefault(emptyMap())
}
