package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.preferences.AppPreferences

/** Feed clean-up for home and search: Shorts-length clips, watched videos, blocked words and channels, duplicates. */
object YouTubeFeedFilter {

    data class Rules(
        val minSeconds: Int = 0,
        val hideWatched: Boolean = false,
        val watchedIds: Set<String> = emptySet(),
        val blockedWords: List<String> = emptyList(),
        val blockedChannels: List<String> = emptyList(),
    )

    /** "1:02:03" / "12:34" / "0:45" to seconds, or null for live badges and unparseable text. */
    internal fun seconds(duration: String?): Int? {
        val parts = duration?.trim()?.split(':') ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0) { acc, p -> acc * 60 + (p.toIntOrNull()?.takeIf { it >= 0 } ?: return null) }
    }

    internal fun parseList(raw: String): List<String> =
        raw.split('\n', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun current(): Rules = Rules(
        minSeconds = AppPreferences.youTubeMinSeconds,
        hideWatched = AppPreferences.youTubeHideWatched,
        watchedIds = if (AppPreferences.youTubeHideWatched) YouTubeLibrary.history().mapTo(HashSet()) { it.videoId } else emptySet(),
        blockedWords = parseList(AppPreferences.youTubeBlockWords),
        blockedChannels = parseList(AppPreferences.youTubeBlockChannels),
    )

    fun keep(v: YouTubeApiClient.YouTubeVideo, r: Rules): Boolean {
        val secs = seconds(v.duration)
        if (secs != null && secs < r.minSeconds) return false
        if (r.hideWatched && v.videoId in r.watchedIds) return false
        val title = v.title.lowercase()
        if (r.blockedWords.any { title.contains(it) }) return false
        val ch = v.channel?.lowercase()
        if (ch != null && r.blockedChannels.any { ch == it }) return false
        return true
    }

    fun videos(list: List<YouTubeApiClient.YouTubeVideo>, r: Rules = current()): List<YouTubeApiClient.YouTubeVideo> =
        list.filter { keep(it, r) }.distinctBy { it.videoId }

    /** Filters each shelf, drops emptied shelves, and keeps a video only in the first shelf that shows it. */
    fun shelves(list: List<YouTubeApiClient.YouTubeShelf>, r: Rules = current()): List<YouTubeApiClient.YouTubeShelf> {
        val seen = HashSet<String>()
        return list.mapNotNull { s ->
            val kept = s.videos.filter { keep(it, r) && seen.add(it.videoId) }
            if (kept.isEmpty()) null else s.copy(videos = kept)
        }
    }
}
