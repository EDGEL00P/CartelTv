package com.carteltv.tv.ui.search

import com.carteltv.tv.data.discovery.DiscoveryFeeds
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.youtube.YouTubeApiClient
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Interface for looking up online results (TMDB / YouTube / Stremio) and returning
 * unified [SearchResult] rows. Callers extend SearchScreen state with [onlineResults] and
 * orchestrate the merge with local catalog hits.
 */
interface OnlineSearchSource {
    suspend fun search(query: String): List<SearchResult>
}

/**
 * Pulls online rows from the three providers. Every source degrades to an empty list when its
 * key is missing, the network is down, or the response cannot be parsed, so callers always get
 * a usable (possibly local-only) result set.
 */
object DefaultOnlineSearchSource : OnlineSearchSource {
    override suspend fun search(query: String): List<SearchResult> {
        val q = query.trim().takeIf { it.length >= 2 } ?: return emptyList()
        // Bound the whole online pass so a slow source can't hang the UI (design: 1.5s timeout).
        val results = kotlinx.coroutines.withTimeoutOrNull(1_500L) {
            coroutineScope {
                val tmdb = async { suspendRunCatching { TmdbOnline.search(q) }.getOrDefault(emptyList()) }
                val youtube = async { suspendRunCatching { YouTubeOnline.search(q) }.getOrDefault(emptyList()) }
                val stremio = async { suspendRunCatching { StremioOnline.search(q) }.getOrDefault(emptyList()) }
                merge(tmdb.await(), youtube.await(), stremio.await())
            }
        }
        return results ?: emptyList()
    }

    /** Merge + de-dupe online rows by normalized title. Exposed for unit testing. */
    fun merge(vararg lists: List<SearchResult>): List<SearchResult> =
        lists.flatMap { it }.distinctBy { it.dedupeKey() }
}

internal object TmdbOnline {
    /** Search TMDB titles (movies and shows) for a text query; returns a list of [SearchResult] or empty. */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val key = AppPreferences.tmdbKey.trim()
        if (key.isBlank()) return@withContext emptyList()
        // search/multi is the text endpoint; find/ expects an external id (tt…), not free text.
        val (url, headers) = DiscoveryFeeds.tmdbRequest(key, "search/multi")
        val joiner = if ('?' in url) '&' else '?'
        val language = java.util.Locale.getDefault().toLanguageTag()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val body = HttpSupport.get("$url${joiner}include_adult=false&language=$language&query=$encoded", headers)
        parseSearch(body)
    }

    /** Parse a TMDB search/multi payload; people and rows without an id are left out. */
    internal fun parseSearch(body: String): List<SearchResult> {
        val root = runCatching { HttpSupport.lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SearchResult>()
        fun text(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        fun poster(obj: JsonObject): String? = text(obj, "poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" }

        (root["results"] as? JsonArray)?.forEach { el ->
            val hit = el as? JsonObject ?: return@forEach
            val type = text(hit, "media_type")?.takeIf { it == "movie" || it == "tv" } ?: return@forEach
            val id = text(hit, "id") ?: return@forEach
            val name = if (type == "tv") text(hit, "name") ?: text(hit, "original_name") else text(hit, "title") ?: text(hit, "original_title")
            name ?: return@forEach
            val year = (if (type == "tv") text(hit, "first_air_date") else text(hit, "release_date"))?.take(4) ?: ""
            out += SearchResult(
                id = id, title = name,
                subtitle = year.takeIf { it.isNotBlank() }?.let { " ($it)" },
                kind = if (type == "tv") "SERIES" else "MOVIE", thumbnailUrl = poster(hit),
                source = "tmdb", displayName = name, url = "tmdb:$type:$id",
            )
        }
        return out
    }
}

private object YouTubeOnline {
    /** Search YouTube for a query and return [SearchResult] rows. */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val key = AppPreferences.youtubeKey.trim()
        if (key.isBlank()) return@withContext emptyList()
        YouTubeApiClient.search(query).map { v ->
            SearchResult(
                id = v.videoId,
                title = v.title,
                subtitle = null,
                kind = "YOUTUBE",
                thumbnailUrl = v.thumb,
                source = "youtube",
                displayName = v.title,
                url = v.videoId,
            )
        }
    }
}

private object StremioOnline {
    /** Browse Stremio addons for a query and return [SearchResult] rows. */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val addons = com.carteltv.tv.data.stremio.StremioAddons.refresh()
        val out = mutableListOf<SearchResult>()
        for (loaded in addons) {
            for (cat in loaded.manifest.catalogs) {
                val supportsSearch = cat.extra.any { it.name == "search" }
                if (!supportsSearch) continue
                val type = cat.type.takeIf { it.isNotBlank() } ?: "movie"
                val metas = suspendRunCatching { loaded.client.catalog(type, cat.id, search = query) }.getOrDefault(emptyList())
                for (m in metas) {
                    out += SearchResult(
                        id = m.id,
                        title = m.name,
                        subtitle = m.releaseInfo,
                        kind = type.uppercase(),
                        thumbnailUrl = m.poster,
                        source = "stremio",
                        displayName = "${m.name} · ${loaded.manifest.name}",
                        url = "stremio:${type}:${m.id}",
                    )
                }
            }
        }
        out
    }
}