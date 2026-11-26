package com.carteltv.tv.data.xtream
import com.carteltv.tv.core.suspendRunCatching

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.util.Base64

/**
 * Full-surface Xtream Codes client (auth, live+Epg, VOD, series).
 * Endpoint families mirrored from the traffic shapes CartelTV's inspiration uses.
 */
open class XtreamClient(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    protected val json get() = com.carteltv.tv.data.network.HttpSupport.lenientJson

    /** Which kind of source this client talks to. */
    open val kind: com.carteltv.tv.data.provider.ProviderType get() = com.carteltv.tv.data.provider.ProviderType.XTREAM

    /**
     * The live output formats this line is allowed to serve ("ts", "m3u8", "hls", ...), exactly as the
     * login's user_info.allowed_output_formats declared them; null until a login said. A link the line
     * does not serve arrives broken or not at all, so [liveUrl] and the timeshift builders pick their
     * extension from this set instead of always guessing HLS.
     */
    var allowedFormats: Set<String>? = null

    /** The XMLTV guide the source supplies itself, or blank when it has none. */
    open fun defaultGuideUrl(): String {
        val u = URLEncoder.encode(username, "UTF-8")
        val p = URLEncoder.encode(password, "UTF-8")
        return "$baseUrl/xmltv.php?username=$u&password=$p"
    }

    private companion object {
        val DEAD_LINE_STATUSES = setOf("expired", "banned", "disabled")
    }

    internal suspend fun getText(path: String): String =
        com.carteltv.tv.data.network.HttpSupport.get("$baseUrl$path", connectMs = 12_000, readMs = 30_000)

    private suspend fun api(action: String, extra: String = ""): JsonElement = withContext(Dispatchers.IO) {
        val u = URLEncoder.encode(username, "UTF-8")
        val p = URLEncoder.encode(password, "UTF-8")
        val raw = getText("/player_api.php?username=$u&password=$p&action=$action$extra")
        json.parseToJsonElement(raw.ifBlank { "[]" })
    }

    @Serializable data class UserInfo(
        val username: String? = null,
        val status: String? = null,
        @SerialName("is_trial") val isTrial: String? = null,
        @SerialName("max_connections") val maxConnections: Int? = null,
        @SerialName("active_cons") val activeCons: Int? = null,
        /** Unix-seconds string on real servers (XCIPTV stores the same shape); null when unlimited. */
        @SerialName("exp_date") val expiryDate: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("allowed_output_formats") val allowedOutputFormats: List<String>? = null,
    )
    @Serializable data class ServerInfo(val url: String? = null, val port: String? = null)

    /** A cheap reachability check for health monitoring; sources that are costly to sign in to override it. */
    open suspend fun ping() { authenticate() }

    open suspend fun authenticate(): Pair<UserInfo?, ServerInfo?> {
        val el = api("")
        val obj = el.jsonObject
        val userInfo = obj["user_info"] as? JsonObject
            ?: throw IllegalStateException("Unexpected auth payload")
        // Standard Xtream rejection carries auth:0 + status; legacy shapes send an empty {}.
        val auth = userInfo["auth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        if (auth == 0) {
            val status = (userInfo["status"] as? JsonPrimitive)?.content
            throw IllegalStateException(status ?: "Credentials rejected")
        }
        val user = json.decodeFromJsonElement<UserInfo>(userInfo)
        // Panels can answer auth:1 and still flag the line; "Active" is the only status that streams.
        user.status?.trim()?.takeIf { it.lowercase() in DEAD_LINE_STATUSES }?.let { throw IllegalStateException(it) }
        allowedFormats = normalizeOutputFormats(user.allowedOutputFormats)
        val server = obj["server_info"]?.let { json.decodeFromJsonElement<ServerInfo>(it) }
        return user to server
    }

    @Serializable data class Category(val category_id: String = "", val category_name: String = "")
    @Serializable data class LiveStream(
        val num: Int = 0,
        val name: String = "",
        @SerialName("stream_id") val streamId: Long = 0,
        @SerialName("stream_icon") val icon: String? = null,
        @SerialName("epg_channel_id") val epgId: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
        /** Server-side catch-up window in days; blank/null/0 = this channel has none. */
        @SerialName("tv_archive") val tvArchive: String? = null,
        @SerialName("tv_archive_duration") val tvArchiveDuration: String? = null,
        /** Provider-supplied play URL that replaces the built-in /live/ shape when present. */
        @SerialName("direct_source") val directSource: String? = null,
        /** Provider-level stream-id override some portals use in their own URLs. */
        @SerialName("custom_sid") val customSid: String? = null,
    ) {
        val catchupDays: Int get() = tvArchive?.trim()?.toIntOrNull() ?: tvArchiveDuration?.trim()?.toIntOrNull() ?: 0
    }

    open suspend fun liveCategories(): List<Category> = englishOnly(decodeList(api("get_live_categories")))
    open suspend fun liveStreams(categoryId: String? = null): List<LiveStream> =
        decodeList<LiveStream>(api("get_live_streams", categoryId?.let { "&category_id=$it" } ?: ""))
            .filter { keep(it.name, it.categoryId, liveBlocked()) }

    @Serializable data class EpgEntry(
        val title: String? = null,
        val description: String? = null,
        @SerialName("start") val start: String? = null,
        @SerialName("end") val end: String? = null,
        @SerialName("start_timestamp") val startTs: String? = null,
        @SerialName("stop_timestamp") val stopTs: String? = null,
    )

    /** Providers vary: titles may be base64. Normalize. */
    open suspend fun shortEpg(streamId: Long, limit: Int = 3): List<EpgEntry> {
        val el = api("get_short_epg", "&stream_id=$streamId&limit=$limit")
        val listings = el.jsonObject["epg_listings"]?.jsonArray ?: return emptyList()
        return listings.mapNotNull {
            runCatching { json.decodeFromJsonElement<EpgEntry>(it) }.getOrNull()
        }.map { ep ->
            fun maybeB64(s: String?): String? =
                s?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() ?: it }
            ep.copy(title = maybeB64(ep.title) ?: ep.title, description = maybeB64(ep.description) ?: ep.description)
        }
    }

    /** The channel's whole listing window (past and upcoming); falls back to [shortEpg] when the provider has no table. */
    open suspend fun epgTable(streamId: Long): List<EpgEntry> {
        val listings = suspendRunCatching {
            api("get_simple_data_table", "&stream_id=$streamId").jsonObject["epg_listings"]?.jsonArray
        }.getOrNull()
        val decoded = listings?.mapNotNull { runCatching { json.decodeFromJsonElement<EpgEntry>(it) }.getOrNull() }.orEmpty()
        if (decoded.isEmpty()) return shortEpg(streamId, 12)
        fun maybeB64(s: String?): String? =
            s?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() ?: it }
        return decoded.map { ep ->
            ep.copy(title = maybeB64(ep.title) ?: ep.title, description = maybeB64(ep.description) ?: ep.description)
        }
    }

    @Serializable data class VodStream(
        val name: String = "",
        @SerialName("stream_id") val streamId: Long = 0,
        @SerialName("stream_icon") val icon: String? = null,
        val rating: String? = null,
        @SerialName("container_extension") val ext: String? = "mp4",
        @SerialName("category_id") val categoryId: String? = null,
    )
    @Serializable data class VodInfo(
        val name: String? = null,
        val plot: String? = null,
        val cast: String? = null,
        val director: String? = null,
        val genre: String? = null,
        val duration: String? = null,
        val releasedate: String? = null,
    )

    open suspend fun vodStreams(categoryId: String? = null): List<VodStream> =
        decodeList<VodStream>(api("get_vod_streams", categoryId?.let { "&category_id=$it" } ?: ""))
            .filter { keep(it.name, it.categoryId, vodBlocked()) }
    open suspend fun movieCategories(): List<Category> = englishOnly(decodeList(api("get_vod_categories")))

    open suspend fun vodInfo(vodId: Long): VodInfo? {
        val el = api("get_vod_info", "&vod_id=$vodId")
        val info = el.jsonObject["info"] ?: return null
        return runCatching { json.decodeFromJsonElement<VodInfo>(info) }.getOrNull()
    }

    @Serializable data class Series(
        @SerialName("series_id") val seriesId: Long = 0,
        val name: String = "",
        val cover: String? = null,
        val plot: String? = null,
        val cast: String? = null,
        val director: String? = null,
        val genre: String? = null,
        val releaseDate: String? = null,
        @SerialName("last_modified") val lastModified: String? = null,
        val rating: String? = null,
        @SerialName("rating_5based") val rating5based: Double? = null,
        @SerialName("backdrop_path") val backdrop: List<String>? = null,
        @SerialName("youtube_trailer") val youtubeTrailer: String? = null,
        @SerialName("episode_run_time") val episodeRunTime: String? = null,
        @SerialName("category_id") val categoryId: String? = null,
    )
    /** Per-episode metadata from get_series_info's episode objects; panels differ in what they fill, so every
     *  member is optional and episodes without the block decode exactly as they always have. */
    @Serializable data class EpisodeInfo(
        @SerialName("movie_image") val movieImage: String? = null,
    )
    @Serializable data class Episode(
        val id: String = "",
        val title: String = "",
        @SerialName("episode_num") val episodeNum: Int = 0,
        @SerialName("container_extension") val ext: String? = "mp4",
        /** The episode's info block, which carries the per-episode still on panels that send one. */
        val info: EpisodeInfo? = null,
        /** The same still some panels mirror at the episode's top level instead of inside the info block. */
        @SerialName("movie_image") val movieImage: String? = null,
        /** Rarer mirror of the still under a second top-level name. */
        @SerialName("cover_big") val coverBig: String? = null,
    ) {
        /** The episode's own still, whichever member the panel filled; blank reads as missing so callers fall back. */
        val art: String? get() = sequenceOf(info?.movieImage, movieImage, coverBig).firstOrNull { !it.isNullOrBlank() }
    }

    open suspend fun seriesCategories(): List<Category> = englishOnly(decodeList(api("get_series_categories")))
    open suspend fun series(categoryId: String? = null): List<Series> =
        decodeList<Series>(api("get_series", categoryId?.let { "&category_id=$it" } ?: ""))
            .filter { keep(it.name, it.categoryId, seriesBlocked()) }

    /** episodes keyed by season number, e.g. info.episodes["1"][0..n] */
    open suspend fun seriesEpisodes(seriesId: Long): Map<Int, List<Episode>> {
        val el = api("get_series_info", "&series_id=$seriesId")
        val eps = el.jsonObject["episodes"]?.jsonObject ?: return emptyMap()
        val out = mutableMapOf<Int, List<Episode>>()
        for ((k, v) in eps) {
            val arr = v.jsonArray
            out[k.toIntOrNull() ?: continue] = arr.mapNotNull {
                runCatching { json.decodeFromJsonElement<Episode>(shedUnreadableEpisodeMembers(it)) }.getOrNull()
            }
        }
        return out
    }

    /**
     * Episodes decode one by one, so one odd member must not cost the whole episode its place on the rail.
     * Panels disagree on the members the model reads for artwork: some send the episode's `info` as a bare
     * string, some send the art mirrors as non-strings. Those shapes are shed here — the member goes back to
     * being ignored, exactly as it was before the model learned it.
     */
    private fun shedUnreadableEpisodeMembers(el: JsonElement): JsonElement {
        val obj = el as? JsonObject ?: return el
        val kept = obj.entries.filterNot { (key, value) ->
            when (key) {
                "info" -> value !is JsonObject && value !is JsonNull
                "movie_image", "cover_big" -> value !is JsonPrimitive
                else -> false
            }
        }
        return if (kept.size == obj.size) obj else JsonObject(kept.associate { it.key to it.value })
    }

    /** Playlist-declared headers/DRM for this exact URL; providers without metadata return null. */
    open fun streamMeta(url: String): StreamMetadata? = null

    /** Path segments must be URL-safe: providers reject raw spaces/symbols in credentials. */
    protected fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** True when the line may serve raw MPEG-TS; unknown lists keep the HLS default the app always used. */
    private fun servesTs(): Boolean = allowedFormats?.let { "ts" in it || "mpegts" in it } ?: false

    /**
     * [hls] null picks what the line serves: TS first when allowed, because one open socket rides an
     * overloaded panel (whole audience on one match) far better than a per-segment request storm;
     * HLS when the line does not serve TS or never said. True/false force one shape.
     */
    open fun liveUrl(id: Long, hls: Boolean? = null): String {
        val useHls = hls ?: !servesTs()
        return "$baseUrl/live/${enc(username)}/${enc(password)}/$id.${if (useHls) "m3u8" else "ts"}"
    }

    /**
     * Xtream catch-up via the query form (widely supported): the live URL narrowed
     * to an archive window. `utc` = window start, `ltc` = window end, epoch seconds.
     * Providers without timeshift ignore the params and serve live.
     */
    open fun timeshiftUrl(id: Long, startMs: Long, endMs: Long, hls: Boolean? = null): String =
        "${liveUrl(id, hls)}?utc=${startMs / 1000}&ltc=${endMs / 1000}"

    /**
     * Catch-up via the dedicated path form used by XCIPTV:
     * /timeshift/{user}/{pass}/{duration-minutes}/{Y-m-d:H:M:S start}/{id}.{ext}
     * Some servers only honor this one; try it when [timeshiftUrl] fails to start.
     */
    open fun timeshiftPathUrl(id: Long, startMs: Long, durationMin: Int, hls: Boolean? = null): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val start = fmt.format(java.util.Date(startMs))
        return "$baseUrl/timeshift/${enc(username)}/${enc(password)}/$durationMin/" +
            URLEncoder.encode(start, "UTF-8") + "/$id.${if (hls ?: !servesTs()) "m3u8" else "ts"}"
    }

    open fun movieUrl(id: Long, ext: String?): String = "$baseUrl/movie/${enc(username)}/${enc(password)}/$id.${ext ?: "mp4"}"
    open fun episodeUrl(id: String, ext: String?): String = "$baseUrl/series/${enc(username)}/${enc(password)}/$id.${ext ?: "mp4"}"

    private fun englishOnly(cats: List<Category>): List<Category> =
        if (com.carteltv.tv.data.preferences.AppPreferences.englishOnly) {
            val wanted = com.carteltv.tv.data.preferences.AppPreferences.contentLanguages
            cats.filter { LanguageFilter.keep(it.category_name, wanted) }
        } else cats

    private fun keep(name: String, categoryId: String?, blocked: Set<String>): Boolean =
        !com.carteltv.tv.data.preferences.AppPreferences.englishOnly ||
            (categoryId !in blocked && LanguageFilter.keep(name, com.carteltv.tv.data.preferences.AppPreferences.contentLanguages))

    private var liveBlockedIds: Set<String>? = null
    private var vodBlockedIds: Set<String>? = null
    private var seriesBlockedIds: Set<String>? = null
    private var blockedFor: Set<LanguageFilter.Lang>? = null

    /** Category blocks depend on the picked languages, so a new selection starts them over. */
    private fun syncBlocked() {
        val wanted = com.carteltv.tv.data.preferences.AppPreferences.contentLanguages
        if (blockedFor != wanted) {
            blockedFor = wanted
            liveBlockedIds = null; vodBlockedIds = null; seriesBlockedIds = null
        }
    }
    private suspend fun liveBlocked(): Set<String> {
        syncBlocked(); val bf = requireNotNull(blockedFor)
        return liveBlockedIds ?: LanguageFilter.blockedCategoryIds(rawCategories("get_live_categories"), bf).also { liveBlockedIds = it }
    }
    private suspend fun vodBlocked(): Set<String> {
        syncBlocked(); val bf = requireNotNull(blockedFor)
        return vodBlockedIds ?: LanguageFilter.blockedCategoryIds(rawCategories("get_vod_categories"), bf).also { vodBlockedIds = it }
    }
    private suspend fun seriesBlocked(): Set<String> {
        syncBlocked(); val bf = requireNotNull(blockedFor)
        return seriesBlockedIds ?: LanguageFilter.blockedCategoryIds(rawCategories("get_series_categories"), bf).also { seriesBlockedIds = it }
    }
    private suspend fun rawCategories(action: String): List<Category> =
        suspendRunCatching { decodeList<Category>(api(action)) }.getOrDefault(emptyList())

    private inline fun <reified T> decodeList(el: JsonElement): List<T> {
        val arr = if (el is JsonArray) el else (el as? JsonObject)?.values?.firstOrNull() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }
    }
}

/** Login-declared output formats, lowercased and blank-free; null when the panel said nothing usable. */
internal fun normalizeOutputFormats(raw: List<String>?): Set<String>? =
    raw?.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }?.toSet()?.takeIf { it.isNotEmpty() }
