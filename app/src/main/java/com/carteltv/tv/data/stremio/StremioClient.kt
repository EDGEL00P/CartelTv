package com.carteltv.tv.data.stremio

import com.carteltv.tv.data.network.isHttpUrl
import com.carteltv.tv.core.suspendRunCatching

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stremio addon protocol v3 (stremio.github.io/addon-protocol):
 *   manifest: GET {base}/manifest.json
 *   catalog : GET {base}/catalog/{type}/{id}/{extra}.json
 *   meta    : GET {base}/meta/{type}/{id}.json
 *   streams : GET {base}/stream/{type}/{id}.json
 *
 * Streams arrive either as plain urls (play direct) or infoHash+sources
 * (torrents → bridged through the linked debrid provider).
 */
class StremioClient(val baseUrl: String) {

    private val json get() = com.carteltv.tv.data.network.HttpSupport.lenientJson

    /** Addon root = manifest URL minus its filename; everything hangs off this. */
    private val root: String = baseUrl.removeSuffix("/manifest.json").trimEnd('/')

    @Serializable data class Manifest(
        val id: String = "",
        val name: String = "",
        val version: String = "0",
        val description: String? = null,
        val types: List<String> = emptyList(),
        val catalogs: List<CatalogDecl> = emptyList(),
        val resources: List<JsonElement> = emptyList(),
        @SerialName("logo") val logo: String? = null,
    ) {
        @Serializable data class CatalogDecl(
            val type: String = "movie",
            val id: String = "",
            val name: String? = null,
            val extra: List<ExtraDecl> = emptyList(),
        ) {
            @Serializable data class ExtraDecl(val name: String = "", val isSelected: Boolean = false, val isRequired: Boolean = false)
        }
    }

    @Serializable data class Metum(
        val id: String = "",
        val type: String? = null,
        val name: String = "",
        val poster: String? = null,
        val background: String? = null,
        val logo: String? = null,
        val description: String? = null,
        val releaseInfo: String? = null,
        val imdbRating: String? = null,
        val genres: List<String> = emptyList(),
        val cast: List<String> = emptyList(),
        val director: List<String> = emptyList(),
        val runtime: String? = null,
        val videos: List<Video> = emptyList(),
        val aliases: JsonElement? = null,
    ) {
        @Serializable data class Video(
            val id: String = "",
            val title: String? = null,
            val season: Int? = null,
            val episode: Int? = null,
            val thumbnail: String? = null,
            val overview: String? = null,
            val released: String? = null,
        )
    }

    @Serializable data class VideoMeta(
        val id: String = "",
        val name: String = "",
        val poster: String? = null,
        val description: String? = null,
        val releaseInfo: String? = null,
        val imdbRating: String? = null,
        val runtime: String? = null,
    )

    @Serializable data class StreamTuple(
        val title: String? = null,
        val url: String? = null,               // direct http(s)
        val infoHash: String? = null,          // torrent
        val sources: List<String> = emptyList(),  // tracker announce urls
        val behaviorHints: JsonObject? = null,
        val name: String? = null,
        val description: String? = null,
        /** The add-on that answered, for grouping and hiding a source. */
        val addon: String? = null,
        /** Release file name from `behaviorHints.filename`, when the add-on sends one. */
        val filename: String? = null,
        /** Already held by the linked debrid account. */
        val cached: Boolean = false,
        /** Short tags the picker shows as chips ("⚡ TorBox", "1080p", "HDR"); empty for addon streams, which describe themselves in text. */
        val badges: List<String> = emptyList(),
    ) {
        /** Everything the release parser can read: the title, the description and the file name. */
        val releaseText: String get() = listOfNotNull(filename, title, description, name).joinToString(" ")
    }

    private suspend fun getJson(url: String): String =
        com.carteltv.tv.data.network.HttpSupport.get(url, headers = mapOf("Accept" to "application/json"), connectMs = 10_000, readMs = 25_000)

    private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    suspend fun manifest(): Manifest = withContext(Dispatchers.Default) {
        val url = if (baseUrl.endsWith("/manifest.json")) baseUrl else baseUrl.trimEnd('/') + "/manifest.json"
        json.decodeFromString<Manifest>(getJson(url))
    }

    suspend fun catalog(type: String, catalogId: String, skip: Int = 0, genre: String? = null, search: String? = null): List<Metum> =
        withContext(Dispatchers.Default) {
            val extra = buildString {
                if (skip > 0) append("&skip=$skip")
                if (genre != null && genre != "All") append("&genre=$genre")
                if (search != null && search.isNotBlank()) append("&search=${java.net.URLEncoder.encode(search, "UTF-8")}")
            }.removePrefix("&")
            val url = "$root/catalog/$type/$catalogId" + (if (extra.isNotBlank()) "/$extra" else "") + ".json"
            val response = suspendRunCatching { json.parseToJsonElement(getJson(url)) as? JsonObject }.getOrNull()
                ?: return@withContext emptyList()
            response.arr("metas").mapNotNull { el ->
                runCatching { json.decodeFromJsonElement<Metum>(el) }.getOrNull()
            }
        }

    suspend fun meta(type: String, id: String): Metum? = withContext(Dispatchers.Default) {
        val rootEl = suspendRunCatching { json.parseToJsonElement(getJson("$root/meta/$type/$id.json")) as? JsonObject }.getOrNull()
            ?: return@withContext null
        (rootEl["meta"])?.let { runCatching { json.decodeFromJsonElement<Metum>(it) }.getOrNull() }
    }

    suspend fun streams(type: String, id: String): List<StreamTuple> = withContext(Dispatchers.Default) {
        val rootEl = suspendRunCatching { json.parseToJsonElement(getJson("$root/stream/$type/$id.json")) as? JsonObject }.getOrNull()
            ?: return@withContext emptyList()
        rootEl.arr("streams").mapNotNull { el ->
            (el as? JsonObject)?.let { o ->
                val hints = o["behaviorHints"] as? JsonObject
                val filename = (hints?.get("filename") as? JsonPrimitive)?.content
                StreamTuple(
                    title = o.str("title") ?: o.str("name"),
                    url = o.str("url"),
                    infoHash = o.str("infoHash"),
                    sources = (o["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                    behaviorHints = hints,
                    name = o.str("name"),
                    description = o.str("description"),
                    filename = filename,
                )
            }
        }
    }

    companion object {
        /** Official Cinemeta — the metadata backbone of the Stremio ecosystem. */
        const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

        /** Minimal, sane default set. */
        val DEFAULT_ADDONS = listOf(CINEMETA)

        fun normalizeBaseUrl(raw: String): String = raw.trim().let {
            when {
                it.isBlank() -> it
                it.startsWith("stremio://", ignoreCase = true) -> "https://" + it.substring("stremio://".length)
                it.isHttpUrl() -> it
                else -> "https://$it"
            }
        }.trimEnd('/')
    }
}

/**
 * Alternate titles from Cinemeta meta: entries may be plain strings or
 * {title|name} objects; blank and malformed entries are skipped.
 */
fun StremioClient.Metum.titleAliases(): List<String> =
    (aliases as? JsonArray).orEmpty().mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
            is JsonObject ->
                ((element["title"] as? JsonPrimitive) ?: (element["name"] as? JsonPrimitive))
                    ?.content?.takeIf { it.isNotBlank() }
            else -> null
        }
    }.distinct()
