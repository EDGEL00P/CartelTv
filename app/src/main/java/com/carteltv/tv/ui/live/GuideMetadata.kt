package com.carteltv.tv.ui.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rich metadata for a guide programme, matched by title.
 * Cinemeta (the Stremio metadata backbone, always onboard) answers first; the
 * vaulted TMDB key covers what Cinemeta misses. Row law: silent on a miss — the
 * panel keeps its plain EPG text, so the guide ships invisible until fed.
 */
object GuideMetadata {

    /** What the details panel can add on top of the EPG line. [backdrop] is a landscape frame where the source has one; [poster] a portrait. */
    data class Rich(
        val poster: String? = null,
        val backdrop: String? = null,
        val year: String? = null,
        val rating: String? = null,
        val genres: String = "",
        val overview: String? = null,
    )

    private const val FRESH_MS = 24 * 60 * 60_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Rich?>>()
    private val gate = Semaphore(2)
    private val json get() = com.carteltv.tv.data.network.HttpSupport.lenientJson

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private val YEAR_SUFFIX = Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$")
    private val SEPARATOR_TAIL = Regex("\\s*[|\u2022\u2013]\\s.*$")
    private val QUALITY_SUFFIX = Regex("\\s*\\b(?:HD|SD|4K|UHD|FHD|HQ)\\b\\s*$")
    private val LANGUAGE_PREFIX = Regex("^(?:[A-Z]{2}\\s+)+")
    private val EPISODE_TAIL = Regex("\\s+[sS]\\d{1,2}\\s*[eE]\\s*\\d{1,3}.*$")

    /** Strips EPG noise: a parenthesised year, separator tails and quality tags. */
    internal fun cleanTitle(raw: String): String =
        raw.replace(YEAR_SUFFIX, "")
            .replace(SEPARATOR_TAIL, "")
            .replace(QUALITY_SUFFIX, "")
            .trim()

    /** The query title for continue-watching art: drops the EPG language prefix ("EN ") and the
        season/episode tail, so "EN My Name Is Earl S1 E2" searches as "My Name Is Earl". */
    internal fun cwLookupTitle(raw: String): String = cleanTitle(
        raw.trim()
            .replace(LANGUAGE_PREFIX, "")
            .replace(EPISODE_TAIL, ""),
    )

    /** Lookup key for continue-watching art: [cwLookupTitle] reduced to letters and digits. */
    internal fun cwArtKey(raw: String): String =
        cwLookupTitle(raw).lowercase().filter { it.isLetterOrDigit() }

    suspend fun lookup(rawTitle: String): Rich? {
        val q = cleanTitle(rawTitle)
        if (q.length < 2) return null
        cache[q]?.let { (at, v) -> if (System.currentTimeMillis() - at < FRESH_MS) return v }
        val v = gate.withPermit { withContext(Dispatchers.IO) { cinemeta(q) ?: tmdb(q) } }
        cache[q] = System.currentTimeMillis() to v
        return v
    }

    private fun get(url: String, headers: Map<String, String> = mapOf("Accept" to "application/json")): JsonObject? =
        runCatching {
            json.parseToJsonElement(com.carteltv.tv.data.network.HttpSupport.getBlocking(url, headers)) as? JsonObject
        }.getOrNull()

    /** Cinemeta search: one request per media type, and hits already carry everything we show. */
    private fun cinemeta(q: String): Rich? {
        val root = com.carteltv.tv.data.stremio.StremioClient.CINEMETA.removeSuffix("manifest.json").trimEnd('/')
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        for (type in listOf("movie", "series")) {
            val hit = get("$root/catalog/$type/top/search=$enc.json")?.arr("metas")?.firstOrNull()?.jsonObject
                ?: continue
            return Rich(
                poster = hit.str("poster"),
                backdrop = hit.str("background"),
                year = hit.str("releaseInfo")?.take(4)?.takeIf { it.isNotBlank() },
                rating = hit.str("imdbRating")?.takeIf { it.isNotBlank() && it != "0" },
                genres = hit.arr("genres").mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" \u00b7 "),
                overview = hit.str("description")?.takeIf { it.isNotBlank() },
            )
        }
        return null
    }

    /** TMDB multi-search, the fallback when Cinemeta misses and a key is vaulted. */
    private fun tmdb(q: String): Rich? {
        val key = com.carteltv.tv.data.preferences.AppPreferences.tmdbKey.trim()
        if (key.isBlank()) return null
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val (url, headers) = com.carteltv.tv.data.discovery.DiscoveryFeeds.tmdbRequest(
            key, "search/multi?query=$enc&include_adult=false",
        )
        val results = get(url, headers)?.arr("results")?.mapNotNull { it as? JsonObject } ?: return null
        val content = results.filter { it.str("media_type") == "movie" || it.str("media_type") == "tv" }
        val hit = content.firstOrNull { (it.str("title") ?: it.str("name"))?.equals(q, ignoreCase = true) == true }
            ?: content.firstOrNull()
            ?: return null
        val isTv = hit.str("media_type") == "tv"
        return Rich(
            poster = com.carteltv.tv.data.discovery.DiscoveryFeeds.posterUrl(hit.str("poster_path")),
            backdrop = com.carteltv.tv.data.discovery.DiscoveryFeeds.backdropUrl(hit.str("backdrop_path")),
            year = (hit.str("release_date") ?: hit.str("first_air_date"))?.take(4)?.takeIf { it.isNotBlank() },
            rating = hit.str("vote_average")?.toFloatOrNull()?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it) },
            genres = hit.arr("genre_ids").mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
                .mapNotNull { (if (isTv) TV_GENRES else MOVIE_GENRES)[it] }.joinToString(" \u00b7 "),
            overview = hit.str("overview")?.takeIf { it.isNotBlank() },
        )
    }

    /** TMDB genre ids → names (movie / tv maps differ; the search response only carries ids). */
    private val MOVIE_GENRES = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
        99 to "Documentary", 18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History",
        27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance", 878 to "Science Fiction",
        10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
    )
    private val TV_GENRES = mapOf(
        10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
        99 to "Documentary", 18 to "Drama", 10762 to "Kids", 10763 to "News", 10764 to "Reality",
        10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
        37 to "Western", 9648 to "Mystery", 10751 to "Family",
    )
}
