package com.carteltv.tv.data.discovery

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A title as TMDB names it, so a movie or show reads the same here as on themoviedb.org. */
object TmdbTitles {
    data class Title(val name: String, val year: Int?, val originalName: String?, val tmdbId: Int? = null)

    private val cache = ConcurrentHashMap<String, Title>()

    /** Null without a TMDB key, offline, or when TMDB has no entry for [imdbId]: callers keep the name they already have. */
    suspend fun forImdb(imdbId: String, series: Boolean): Title? {
        val key = AppPreferences.tmdbKey.trim()
        if (key.isBlank() || !imdbId.startsWith("tt")) return null
        val cacheKey = "$imdbId:$series"
        cache[cacheKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (url, headers) = DiscoveryFeeds.tmdbRequest(key, "find/$imdbId")
                val joiner = if ('?' in url) '&' else '?'
                val language = Locale.getDefault().toLanguageTag()
                val body = HttpSupport.get("$url${joiner}external_source=imdb_id&language=$language", headers)
                parseFind(body, series)
            }.getOrNull()?.also { cache[cacheKey] = it }
        }
    }

    private val seasonCache = ConcurrentHashMap<String, Map<Int, String>>()

    /** Episode number to TMDB's episode name for one season; empty without a key, offline or when TMDB has none. */
    suspend fun seasonEpisodes(imdbId: String, season: Int): Map<Int, String> {
        val show = forImdb(imdbId, series = true)?.tmdbId ?: return emptyMap()
        val cacheKey = "$show:$season"
        seasonCache[cacheKey]?.let { return it }
        val key = AppPreferences.tmdbKey.trim()
        return withContext(Dispatchers.IO) {
            runCatching {
                val (url, headers) = DiscoveryFeeds.tmdbRequest(key, "tv/$show/season/$season")
                val joiner = if ('?' in url) '&' else '?'
                parseSeason(HttpSupport.get("$url${joiner}language=${Locale.getDefault().toLanguageTag()}", headers))
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { seasonCache[cacheKey] = it } ?: emptyMap()
        }
    }

    internal fun parseSeason(body: String): Map<Int, String> {
        val root = runCatching { HttpSupport.lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyMap()
        val episodes = root["episodes"] as? JsonArray ?: return emptyMap()
        return episodes.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val number = (o["episode_number"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            number to name
        }.toMap()
    }

    internal fun parseFind(body: String, series: Boolean): Title? {
        val root = runCatching { HttpSupport.lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val hit = (root[if (series) "tv_results" else "movie_results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        fun text(key: String) = (hit[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val name = text(if (series) "name" else "title") ?: return null
        val year = text(if (series) "first_air_date" else "release_date")?.take(4)?.toIntOrNull()
        return Title(name, year, text(if (series) "original_name" else "original_title"), text("id")?.toIntOrNull())
    }
}
