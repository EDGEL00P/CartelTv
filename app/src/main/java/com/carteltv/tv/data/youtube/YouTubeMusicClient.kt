package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * YouTube Music browse over the WEB_REMIX client. Signed-out only: home shelves are mostly playlists,
 * so a tile carries a playlist id and [queue] resolves it to playable videos.
 */
object YouTubeMusicClient {
    private const val API = "https://music.youtube.com/youtubei/v1/"
    private const val CLIENT_VERSION = "1.20260805.01.00"

    data class Entry(val title: String, val subtitle: String?, val art: String?, val videoId: String?, val playlistId: String?)
    data class Shelf(val eyebrow: String?, val title: String, val entries: List<Entry>)

    suspend fun home(): List<Shelf> {
        val root = call("browse", "\"browseId\":\"FEmusic_home\"") ?: return emptyList()
        val sections = root.obj("contents")?.obj("singleColumnBrowseResultsRenderer")?.arr("tabs")
            ?.firstOrNull()?.obj("tabRenderer")?.obj("content")?.obj("sectionListRenderer")?.arr("contents").orEmpty()
        return sections.mapNotNull { parseShelf(it.obj("musicCarouselShelfRenderer") ?: return@mapNotNull null) }
    }

    /** Playable tracks of a playlist, in queue order. */
    suspend fun queue(playlistId: String, videoId: String? = null): List<YouTubeApiClient.YouTubeVideo> {
        val extra = buildString {
            append("\"playlistId\":${JsonPrimitive(playlistId)}")
            if (videoId != null) append(",\"videoId\":${JsonPrimitive(videoId)}")
        }
        val root = call("next", extra) ?: return emptyList()
        val panel = root.obj("contents")?.obj("singleColumnMusicWatchNextResultsRenderer")?.obj("tabbedRenderer")
            ?.obj("watchNextTabbedResultsRenderer")?.arr("tabs")?.firstOrNull()?.obj("tabRenderer")?.obj("content")
            ?.obj("musicQueueRenderer")?.obj("content")?.obj("playlistPanelRenderer")?.arr("contents").orEmpty()
        return panel.mapNotNull { item ->
            val r = item.obj("playlistPanelVideoRenderer") ?: return@mapNotNull null
            val id = r.str("videoId") ?: return@mapNotNull null
            YouTubeApiClient.YouTubeVideo(
                videoId = id,
                title = runs(r.obj("title")).ifBlank { return@mapNotNull null },
                channel = r.obj("shortBylineText")?.let(::runs)?.ifBlank { null },
                duration = r.obj("lengthText")?.let(::runs)?.ifBlank { null },
                thumb = lastThumb(r.obj("thumbnail")),
            )
        }
    }

    private fun parseShelf(shelf: JsonObject): Shelf? {
        val header = shelf.obj("header")?.obj("musicCarouselShelfBasicHeaderRenderer")
        val title = header?.obj("title")?.let(::runs)?.ifBlank { null } ?: return null
        val entries = shelf.arr("contents").orEmpty().mapNotNull { parseEntry(it.obj("musicTwoRowItemRenderer") ?: return@mapNotNull null) }
        if (entries.isEmpty()) return null
        return Shelf(header.obj("strapline")?.let(::runs)?.ifBlank { null }, title, entries)
    }

    private fun parseEntry(r: JsonObject): Entry? {
        val title = r.obj("title")?.let(::runs)?.ifBlank { null } ?: return null
        val nav = r.obj("navigationEndpoint")
        val videoId = nav?.obj("watchEndpoint")?.str("videoId")
        val playlistId = nav?.obj("watchPlaylistEndpoint")?.str("playlistId")
            ?: nav?.obj("watchEndpoint")?.str("playlistId")
            ?: nav?.obj("browseEndpoint")?.str("browseId")?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
        if (videoId == null && playlistId == null) return null
        val art = lastThumb(r.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")?.obj("thumbnail")?.let { JsonObject(mapOf("thumbnail" to it)) })
        return Entry(title, r.obj("subtitle")?.let(::runs)?.ifBlank { null }, art, videoId, playlistId)
    }

    private suspend fun call(route: String, extra: String): JsonObject? = runCatching {
        val body = "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"$CLIENT_VERSION\",\"hl\":\"en\",\"gl\":\"US\"}},$extra}"
        val (code, text) = HttpSupport.request(
            url = "$API$route?prettyPrint=false", method = "POST", body = body, contentType = "application/json",
            headers = mapOf("Origin" to "https://music.youtube.com", "User-Agent" to YouTubeRequestPolicy.UA_WEB),
        )
        if (code !in 200..299) null else HttpSupport.json.parseToJsonElement(text).jsonObject
    }.getOrNull()

    private fun runs(o: JsonObject?): String =
        o?.arr("runs").orEmpty().joinToString("") { it.str("text").orEmpty() }

    private fun lastThumb(o: JsonObject?): String? =
        o?.obj("thumbnail")?.arr("thumbnails")?.lastOrNull()?.str("url")

    private fun JsonElement.obj(k: String): JsonObject? = (this as? JsonObject)?.get(k) as? JsonObject
    private fun JsonElement.arr(k: String): List<JsonElement>? = ((this as? JsonObject)?.get(k) as? JsonArray)?.toList()
    private fun JsonElement.str(k: String): String? = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
