package com.carteltv.tv.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * DISCOVERY FEEDS (row taxonomy — TRENDING /
 * POPULAR / TOP RATED / UPCOMING — fed by TMDB public endpoints, the
 * key CartelTV already vaults for ForYou).
 *
 * Row law: enhancement-gated (silent when no key — ships
 * invisible until fed"), each row independently droppable when a feed
 * fails; nothing promises a liveliness it can't keep.
 */
object DiscoveryFeeds {

    data class Card(
        val id: Int,
        val title: String,
        val poster: String?,
        val rating: Float?,
        val year: String?,
        val media: String,   // "movie" | "tv"
    )

    private val json get() = com.carteltv.tv.data.network.HttpSupport.lenientJson

    enum class Feed(val path: String, val tvPath: String? = null, val label: String) {
        TRENDING("trending/movie/week", "trending/tv/week", "Trending Now"),
        NOW_PLAYING("movie/now_playing", null, "In Cinemas Now"),
        POPULAR("movie/popular", "tv/popular", "Popular"),
        TOP_RATED("movie/top_rated", "tv/top_rated", "Top Rated"),
        UPCOMING("movie/upcoming", null, "Coming Soon"),
    }

    /** hold-OK cycle order */
    val FEEDS_ORDER = listOf(Feed.TRENDING, Feed.POPULAR, Feed.TOP_RATED, Feed.UPCOMING)

    /** A TMDB request: a v4 read token (a JWT) goes in the Authorization header, a v3 key can only be a query parameter. */
    internal fun tmdbRequest(apiKey: String, path: String): Pair<String, Map<String, String>> {
        val key = apiKey.trim().removePrefix("Bearer ").trim()
        val base = "https://api.themoviedb.org/3/$path"
        return if (key.startsWith("eyJ") && key.count { it == '.' } == 2) {
            base to mapOf("Authorization" to "Bearer $key")
        } else {
            "$base?api_key=$key" to emptyMap()
        }
    }

    private fun fetch(apiKey: String, path: String): List<Card> {
        val (url, headers) = tmdbRequest(apiKey, path)
        val txt = com.carteltv.tv.data.network.HttpSupport.getBlocking(url, headers)
        val arr = json.parseToJsonElement(txt).jsonObject["results"]?.jsonArray ?: return emptyList()
        return arr.take(18).mapNotNull { el ->
            val o = el.jsonObject
            val isTv = path.startsWith("tv/") || path.startsWith("trending/tv")
            Card(
                id = o["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null,
                title = (o["title"] ?: o["name"])?.jsonPrimitive?.content ?: return@mapNotNull null,
                poster = o["poster_path"]?.jsonPrimitive?.content,
                rating = o["vote_average"]?.jsonPrimitive?.content?.toFloatOrNull()
                    ?.takeIf { it > 0 },
                year = ((o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.content)
                    ?.take(4)?.takeIf { it.isNotBlank() },
                media = if (isTv) "tv" else "movie",
            )
        }
    }

    /** One row's content; a media flip interleaves tv into movie-dominated feeds. [path] overrides the feed's movie path. */
    suspend fun row(apiKey: String, feed: Feed, mixTv: Boolean = false, path: String = feed.path): List<Card> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val primary = async { runCatching { fetch(apiKey, path) }.getOrDefault(emptyList()) }
                val secondary: List<Card>? = if (mixTv && feed.tvPath != null)
                    runCatching { fetch(apiKey, feed.tvPath) }.getOrDefault(emptyList())
                else null
                val p = primary.await()
                if (secondary == null) return@coroutineScope p
                // weave: 2 movie : 1 tv
                val s = secondary
                val out = mutableListOf<Card>(); var pi = 0; var si = 0
                while (pi < p.size || si < s.size) {
                    repeat(2) { if (pi < p.size) out += p[pi++] }
                    if (si < s.size) out += s[si++]
                }
                out
            }
        }

    /** One row by raw TMDB path, for the TV leg of a feed. */
    suspend fun path(apiKey: String, path: String): List<Card> = withContext(Dispatchers.IO) {
        runCatching { fetch(apiKey, path) }.getOrDefault(emptyList())
    }

    /** Discover by genre: the same row shape, one media kind, TMDB's popularity order. */
    suspend fun discover(apiKey: String, media: String, genreId: Int?): List<Card> = withContext(Dispatchers.IO) {
        val kind = if (media == "tv") "tv" else "movie"
        val genre = genreId?.let { "&with_genres=$it" } ?: ""
        runCatching { fetch(apiKey, "discover/$kind?sort_by=popularity.desc$genre") }.getOrDefault(emptyList())
    }

    /** The chips both Discover and shelf editing offer: a short, popular set, not a taxonomy. */
    val GENRES = listOf(
        "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80,
        "Documentary" to 99, "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "Horror" to 27,
        "Mystery" to 9648, "Romance" to 10749, "Sci-Fi" to 878, "Thriller" to 53, "War" to 10752,
    )

    /** poster → absolute URL (TMDB 342w) */
    fun posterUrl(path: String?): String? =
        path?.let { "https://image.tmdb.org/t/p/w342$it" }

    /** backdrop → absolute URL (TMDB 780w: banner-wide without weighing on a TV's decoder) */
    internal fun backdropUrl(path: String?): String? = path?.let { "https://image.tmdb.org/t/p/w780$it" }
}
