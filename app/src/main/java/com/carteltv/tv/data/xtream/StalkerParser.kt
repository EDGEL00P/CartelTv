package com.carteltv.tv.data.xtream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Reads Stalker/Ministra portal replies. Pure, so the shapes can be tested without a portal. */
object StalkerParser {
    data class Channel(
        val id: String,
        val name: String,
        val number: Int,
        val logo: String,
        val cmd: String,
        val genreId: String,
        val xmltvId: String,
        val needsLink: Boolean,
    ) {
        val longId: Long get() = id.toLongOrNull()?.takeIf { it >= 0 } ?: M3uParser.idOf(id)
    }

    private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonElement?.flag(): Boolean = str().let { it == "1" || it.equals("true", true) }

    /** Portals reply `{"js": ...}`; null when the body is not JSON (their auth failures are plain text). */
    fun js(body: String, json: kotlinx.serialization.json.Json): JsonElement? =
        runCatching { json.parseToJsonElement(body).jsonObject["js"] }.getOrNull()

    fun token(js: JsonElement?): String? = (js as? JsonObject)?.get("token").str().ifBlank { null }

    /** `js.status` 1 means the portal refused the device; 2 asks for a login this client does not do. */
    fun profileError(js: JsonElement?): String? {
        val o = js as? JsonObject ?: return "The portal did not return a profile"
        return when (o["status"].str()) {
            "1" -> (o["msg"].str().ifBlank { o["block_msg"].str() }).ifBlank { "The portal refused this device" }
            "2" -> "This portal also asks for a username and password, which is not supported yet"
            else -> null
        }
    }

    fun genres(js: JsonElement?): List<XtreamClient.Category> =
        (js as? JsonArray).orEmpty().mapNotNull { g ->
            val o = g as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str()
            val title = o["title"].str().ifBlank { o["name"].str() }
            if (id.isBlank() || id == "*" || title.isBlank()) null else XtreamClient.Category(id, title)
        }

    fun channels(js: JsonElement?): List<Channel> {
        val data = ((js as? JsonObject)?.get("data") ?: js) as? JsonArray ?: return emptyList()
        return data.mapNotNull { c ->
            val o = c as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str()
            val cmd = o["cmd"].str()
            if (id.isBlank() || cmd.isBlank()) return@mapNotNull null
            Channel(
                id = id, name = o["name"].str().ifBlank { "Channel $id" },
                number = o["number"].str().toIntOrNull() ?: 0,
                logo = o["logo"].str(), cmd = cmd, genreId = o["tv_genre_id"].str(),
                xmltvId = o["xmltv_id"].str(),
                needsLink = o["use_http_tmp_link"].flag() || o["use_load_balancing"].flag(),
            )
        }
    }

    /** The playable address inside a portal command such as `ffmpeg http://host/stream` or `ffrt http://…`. */
    fun playable(cmd: String): String = cmd.trim().substringAfterLast(' ').trim()

    /** True when the channel's own command cannot be played directly and the portal must be asked for a link. */
    fun needsCreateLink(c: Channel): Boolean {
        val url = playable(c.cmd)
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
        return c.needsLink || host == "localhost" || host == "127.0.0.1" || host.isEmpty()
    }

    fun linkFrom(js: JsonElement?): String? =
        (js as? JsonObject)?.get("cmd").str().let(::playable).takeIf { it.contains("://") }

    data class Movie(
        val id: String,
        val name: String,
        val poster: String,
        val cmd: String,
        val rating: String?,
        val categoryId: String,
    ) {
        val longId: Long get() = id.toLongOrNull()?.takeIf { it >= 0 } ?: M3uParser.idOf(id)
    }

    /** Movies from `type=vod&action=get_items`; `js.data[]`, cmd may be `cmd` or `video_url`. */
    fun movies(js: JsonElement?): List<Movie> {
        val data = ((js as? JsonObject)?.get("data") ?: js) as? JsonArray ?: return emptyList()
        return data.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str()
            val cmd = o["cmd"].str().ifBlank { o["video_url"].str() }
            if (id.isBlank()) return@mapNotNull null
            Movie(
                id = id, name = o["name"].str().ifBlank { "Movie $id" },
                poster = o["poster_url"].str().ifBlank { o["screenshot_preview"].str() },
                cmd = cmd,
                rating = o["rating_imdb"].str().ifBlank { null },
                categoryId = o["category_id"].str(),
            )
        }
    }

    /** One entry of the portal's series list. */
    data class SeriesItem(
        val id: String,
        val name: String,
        val poster: String,
        val plot: String,
        val categoryId: String,
    ) {
        val longId: Long get() = id.toLongOrNull()?.takeIf { it >= 0 } ?: M3uParser.idOf(id)
    }

    /** Series from `type=series&action=get_items`; `js.data[]`. */
    fun seriesList(js: JsonElement?): List<SeriesItem> {
        val data = ((js as? JsonObject)?.get("data") ?: js) as? JsonArray ?: return emptyList()
        return data.mapNotNull { s ->
            val o = s as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str()
            if (id.isBlank()) return@mapNotNull null
            SeriesItem(
                id = id, name = o["name"].str().ifBlank { "Series $id" },
                poster = o["poster_url"].str().ifBlank { o["screenshot_preview"].str() },
                plot = o["description"].str(),
                categoryId = o["category_id"].str(),
            )
        }
    }

    data class Episode(
        val id: String,
        val seriesId: String,
        val name: String,
        val num: Int,
        val season: Int,
        val cmd: String,
    )

    /**
     * Episodes from `type=series&action=get_hierarchy&series_id=`: `js` is an array of seasons,
     * each with its own `episodes` array carrying the playable command.
     */
    fun episodes(js: JsonElement?): List<Episode> {
        val seasons = js as? JsonArray ?: return emptyList()
        val out = ArrayList<Episode>()
        seasons.forEachIndexed { i, s ->
            val so = s as? JsonObject ?: return@forEachIndexed
            val seasonNum = so["number"].str().toIntOrNull() ?: (i + 1)
            val eps = so["episodes"] as? JsonArray ?: return@forEachIndexed
            eps.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                val id = o["id"].str()
                val cmd = o["cmd"].str().ifBlank { o["video_url"].str() }
                if (id.isBlank() || cmd.isBlank()) return@forEach
                out.add(
                    Episode(
                        id = id, seriesId = so["id"].str(), name = o["name"].str().ifBlank { "Episode ${o["number"].str()}" },
                        num = o["number"].str().toIntOrNull() ?: 0, season = seasonNum, cmd = cmd,
                    ),
                )
            }
        }
        return out
    }

    /** `type=vod` and `type=series` both resolve links with create_link; only the action differs. */
    fun vodLink(js: JsonElement?): String? = linkFrom(js)

    /** Logos are often paths on the portal host. */
    fun absolute(origin: String, path: String): String? = when {
        path.isBlank() -> null
        path.contains("://") -> path
        path.startsWith("/") -> origin.trimEnd('/') + path
        else -> origin.trimEnd('/') + "/" + path
    }

    /** Candidate API endpoints for an address the user typed, most likely first. */
    fun endpoints(input: String): List<String> {
        val raw = input.trim().let { if (it.contains("://")) it else "http://$it" }.trimEnd('/')
        if (raw.substringBefore('?').endsWith(".php", true)) return listOf(raw)
        val base = raw.removeSuffix("/c").trimEnd('/')
        return if (base.endsWith("/stalker_portal", true)) listOf("$base/server/load.php", "$base/portal.php")
        else listOf("$base/portal.php", "$base/server/load.php", "$base/stalker_portal/server/load.php")
    }

    fun macValid(mac: String) = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac.trim())
}
