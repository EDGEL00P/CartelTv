package com.carteltv.tv.data.watch

/** What to tell Trakt when a player closes, or null when the viewing doesn't count. */
sealed interface Scrobble {
    data class Episode(val show: String, val season: Int, val episode: Int) : Scrobble
    data class Movie(val title: String) : Scrobble
}

/** "Show Name S01E05" — shared by scrobbling and next-episode labelling. */
internal val EPISODE_TITLE = Regex("^(.*?)\\s+S(\\d+)\\s*E(\\d+)", RegexOption.IGNORE_CASE)

/** Derives the scrobble from what was watched and how far; null when it doesn't count. */
fun scrobbleFor(refId: String, title: String, posMs: Long, durMs: Long): Scrobble? {
    if (durMs <= 0 || refId.isBlank()) return null
    if (posMs * 100 / durMs < 80 && posMs < 300_000) return null
    return when {
        refId.startsWith("live:") || refId.startsWith("recording:") -> null
        refId.startsWith("series:") -> {
            val m = EPISODE_TITLE.find(title) ?: return null
            val show = m.groupValues[1].trim()
            val s = m.groupValues[2].toIntOrNull()
            val e = m.groupValues[3].toIntOrNull()
            if (show.isNotBlank() && s != null && e != null) Scrobble.Episode(show, s, e) else null
        }
        else -> Scrobble.Movie(title)
    }
}
