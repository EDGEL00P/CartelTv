package com.carteltv.tv.ui.search

private val NON_ALNUM = Regex("[^a-z0-9]")

/**
 * One row of a merged search: a local catalog hit or an online lookup result.
 *
 * [source] names the origin ("local" | "tmdb" | "youtube" | "stremio"); [kind] is the display
 * tag (LIVE, MOVIE, SERIES, APP, YOUTUBE, STREMIO, …). [url] carries what the screen needs to
 * act on the row — a live/archive stream URL for local hits, a YouTube videoId, a Stremio meta
 * id, or empty for metadata-only TMDB entries. Navigation itself lives in [SearchScreen]; this
 * type only carries data.
 */
data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val kind: String,
    val thumbnailUrl: String? = null,
    val source: String,
    val displayName: String,
    val url: String = "",
    val isLocal: Boolean = false,
    val cachedAt: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
) {
    /** Lower-cased, punctuation-free title used to de-duplicate merged rows. */
    fun dedupeKey(): String = title.lowercase().replace(NON_ALNUM, "")
}