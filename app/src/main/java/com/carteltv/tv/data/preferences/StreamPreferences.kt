package com.carteltv.tv.data.preferences

import android.content.SharedPreferences

/**
 * Stream-list behaviour: order, filters, auto-play and the last link a title played.
 * Kept apart from player preferences because it governs the picker, not playback.
 */
class StreamPreferences(private val sp: SharedPreferences) {

    enum class AutoPlay { MANUAL, FIRST, REGEX }

    enum class Sort { ORIGINAL, BEST, QUALITY, SIZE, SEEDERS, NAME }

    var autoPlay: AutoPlay
        get() = runCatching { AutoPlay.valueOf(sp.getString("stream_autoplay", null) ?: "") }
            .getOrDefault(AutoPlay.MANUAL)
        set(v) = sp.edit().putString("stream_autoplay", v.name).apply()

    /** Release-name expression used by [AutoPlay.REGEX]; blank falls back to the best source. */
    var autoPlayRegex: String
        get() = sp.getString("stream_autoplay_regex", "") ?: ""
        set(v) = sp.edit().putString("stream_autoplay_regex", v.trim()).apply()

    var sort: Sort
        get() = runCatching { Sort.valueOf(sp.getString("stream_sort", null) ?: "") }
            .getOrDefault(Sort.BEST)
        set(v) = sp.edit().putString("stream_sort", v.name).apply()

    /** Resolution labels kept ("2160p", "1080p", …); empty keeps every resolution. */
    var resolutions: Set<String>
        get() = sp.getStringSet("stream_resolutions", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("stream_resolutions", v).apply()

    /** Only Dolby Vision / HDR copies. */
    var hdrOnly: Boolean
        get() = sp.getBoolean("stream_hdr_only", false)
        set(v) = sp.edit().putBoolean("stream_hdr_only", v).apply()

    /** Only sources already cached by the linked debrid account. */
    var cachedOnly: Boolean
        get() = sp.getBoolean("stream_cached_only", false)
        set(v) = sp.edit().putBoolean("stream_cached_only", v).apply()

    /** Source names hidden in the picker (an add-on the user does not want to scrape). */
    var hiddenSources: Set<String>
        get() = sp.getStringSet("stream_hidden_sources", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("stream_hidden_sources", v).apply()

    /** Offer the stream this title played last at the top while its link is still fresh. */
    var reuseLastLink: Boolean
        get() = sp.getBoolean("stream_reuse_last", true)
        set(v) = sp.edit().putBoolean("stream_reuse_last", v).apply()

    var lastLinkMinutes: Int
        get() = sp.getInt("stream_last_minutes", 360).coerceIn(60, 10_080)
        set(v) = sp.edit().putInt("stream_last_minutes", v.coerceIn(60, 10_080)).apply()

    data class LastLink(val url: String, val infoHash: String?, val title: String?, val atMs: Long)

    fun remember(ref: String, url: String, infoHash: String?, title: String?) {
        val payload = listOf(url, infoHash.orEmpty(), title.orEmpty(), System.currentTimeMillis().toString())
            .joinToString("|")
        sp.edit().putString(lastKey(ref), payload).apply()
    }

    fun lastLink(ref: String): LastLink? {
        val parts = sp.getString(lastKey(ref), null)?.split("|") ?: return null
        if (parts.size < 4) return null
        val at = parts[3].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > lastLinkMinutes * 60_000L) return null
        return LastLink(parts[0], parts[1].ifBlank { null }, parts[2].ifBlank { null }, at)
    }

    fun forgetLastLink(ref: String) = sp.edit().remove(lastKey(ref)).apply()

    private fun lastKey(ref: String) = "stream_last_$ref"
}
