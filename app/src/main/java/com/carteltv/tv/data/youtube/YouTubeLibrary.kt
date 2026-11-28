package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** The viewer's own YouTube history, saved list and recent searches, kept on the box. */
object YouTubeLibrary {

    @Serializable
    data class Entry(
        val id: String,
        val title: String,
        val channel: String? = null,
        val duration: String? = null,
        val thumb: String? = null,
        val channelId: String? = null,
        val publishedMs: Long = 0L,
        val publishedText: String? = null,
        val viewsText: String? = null,
    )

    const val HISTORY_CAP = 80
    const val SAVED_CAP = 300
    const val SEARCH_CAP = 12

    /** [item] first, any earlier copy removed, trimmed to [cap]. */
    internal fun <T> front(list: List<T>, item: T, cap: Int, same: (T, T) -> Boolean): List<T> =
        (listOf(item) + list.filterNot { same(it, item) }).take(cap)

    internal fun frontSearch(list: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return front(list, q, SEARCH_CAP) { a, b -> a.equals(b, ignoreCase = true) }
    }

    private fun toEntry(v: YouTubeApiClient.YouTubeVideo) =
        Entry(v.videoId, v.title, v.channel, v.duration, v.thumb, v.channelId, v.publishedMs, v.publishedText, v.viewsText)
    private fun toVideo(e: Entry) =
        YouTubeApiClient.YouTubeVideo(e.id, e.title, e.channel, e.duration, e.thumb, e.channelId, e.publishedMs, e.publishedText, e.viewsText)

    private fun read(raw: String): List<Entry> =
        if (raw.isBlank()) emptyList() else runCatching { HttpSupport.json.decodeFromString<List<Entry>>(raw) }.getOrDefault(emptyList())

    private data class EntryCache(val raw: String?, val entries: List<Entry>)
    @Volatile private var historyCache = EntryCache(null, emptyList())
    @Volatile private var savedCache = EntryCache(null, emptyList())

    private fun historyEntries(): List<Entry> {
        val raw = AppPreferences.youTubeHistoryRaw
        val cur = historyCache
        if (cur.raw == raw) return cur.entries
        val parsed = read(raw)
        historyCache = EntryCache(raw, parsed)
        return parsed
    }

    private fun savedEntries(): List<Entry> {
        val raw = AppPreferences.youTubeSavedRaw
        val cur = savedCache
        if (cur.raw == raw) return cur.entries
        val parsed = read(raw)
        savedCache = EntryCache(raw, parsed)
        return parsed
    }

    fun history(): List<YouTubeApiClient.YouTubeVideo> = historyEntries().map(::toVideo)

    fun noteWatched(v: YouTubeApiClient.YouTubeVideo) {
        AppPreferences.youTubeHistoryRaw = HttpSupport.json.encodeToString(front(historyEntries(), toEntry(v), HISTORY_CAP) { a, b -> a.id == b.id })
    }

    fun removeFromHistory(id: String) {
        AppPreferences.youTubeHistoryRaw = HttpSupport.json.encodeToString(historyEntries().filterNot { it.id == id })
    }

    fun clearHistory() { AppPreferences.youTubeHistoryRaw = "" }

    fun saved(): List<YouTubeApiClient.YouTubeVideo> = savedEntries().map(::toVideo)

    fun isSaved(id: String): Boolean = savedEntries().any { it.id == id }

    /** Adds the video to the top of the saved list, or removes it if already there. Returns true when now saved. */
    fun toggleSaved(v: YouTubeApiClient.YouTubeVideo): Boolean {
        val cur = savedEntries()
        return if (cur.any { it.id == v.videoId }) {
            AppPreferences.youTubeSavedRaw = HttpSupport.json.encodeToString(cur.filterNot { it.id == v.videoId })
            false
        } else {
            AppPreferences.youTubeSavedRaw = HttpSupport.json.encodeToString(front(cur, toEntry(v), SAVED_CAP) { a, b -> a.id == b.id })
            true
        }
    }

    fun searches(): List<String> = AppPreferences.youTubeSearchesRaw.split('\n').filter { it.isNotBlank() }

    fun noteSearch(q: String) { AppPreferences.youTubeSearchesRaw = frontSearch(searches(), q).joinToString("\n") }

}
