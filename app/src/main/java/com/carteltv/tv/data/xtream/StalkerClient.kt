@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.data.xtream

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.provider.ProviderType
import java.io.IOException
import java.net.URLEncoder
import java.util.TimeZone

/**
 * A Stalker/Ministra portal (identified by a MAC address) presented as an Xtream-style client.
 * Live channels, groups, the portal's short guide and the portal's movies and series are supported.
 * Stream links are requested from the portal when playback starts, so [liveUrl] returns a `stalker://` address
 * that [Resolver] turns into the real link inside the player.
 */
class StalkerClient(portal: String, private val mac: String) : XtreamClient(portal, mac.trim().uppercase(), "") {
    override val kind: ProviderType get() = ProviderType.STALKER

    private val tz = TimeZone.getDefault().id
    private val candidates = StalkerParser.endpoints(portal)
    private val origin = candidates.first().substringBefore("://") + "://" +
        candidates.first().substringAfter("://").substringBefore('/')

    @Volatile private var endpoint: String? = null
    @Volatile private var token: String? = null
    @Volatile private var channels: Map<Long, StalkerParser.Channel> = emptyMap()
    private val lock = Any()

    override fun defaultGuideUrl(): String = ""

    private fun headers(withToken: Boolean): Map<String, String> = buildMap {
        put("Cookie", "mac=${mac.trim().uppercase()}; stb_lang=en; timezone=$tz")
        put("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C)")
        put("X-User-Agent", "Model: MAG250; Link: WiFi")
        token?.takeIf { withToken }?.let { put("Authorization", "Bearer $it") }
    }

    private fun get(ep: String, query: String, withToken: Boolean): String =
        HttpSupport.getBlocking("$ep?$query&JsHttpRequest=1-xml", headers(withToken), connectMs = 12_000, readMs = 30_000)

    private fun handshake(ep: String): String? =
        StalkerParser.token(StalkerParser.js(get(ep, "type=stb&action=handshake", false), json))

    /** Finds the working endpoint, takes a token and activates it with a profile call. Throws on refusal. */
    private fun login(): String = synchronized(lock) { loginLocked() }

    private fun loginLocked(): String {
        var last: Throwable? = null
        val eps = endpoint?.let { listOf(it) } ?: candidates
        for (ep in eps) {
            try {
                val t = handshake(ep) ?: continue
                token = t
                val profile = StalkerParser.js(get(ep, "type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r14", true), json)
                StalkerParser.profileError(profile)?.let { throw IllegalStateException(it) }
                endpoint = ep
                return ep
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("Could not reach the portal${last?.message?.let { ": ${HttpSupport.redact(it)}" } ?: ""}")
    }

    /** A portal call; a plain-text auth failure (their way of saying the token lapsed) triggers one fresh login. */
    private fun call(query: String): kotlinx.serialization.json.JsonElement? {
        var ep = endpoint ?: login()
        repeat(2) { attempt ->
            val body = get(ep, query, true)
            StalkerParser.js(body, json)?.let { return it }
            if (attempt == 0) { token = null; endpoint = null; ep = login() }
        }
        return null
    }

    override suspend fun authenticate(): Pair<UserInfo?, ServerInfo?> {
        if (!StalkerParser.macValid(mac)) throw IllegalStateException("MAC address must look like 00:1A:79:12:34:56")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { login() }
        return UserInfo(username = mac.trim().uppercase()) to null
    }

    private fun filterCats(cats: List<Category>): List<Category> =
        if (AppPreferences.englishOnly) cats.filter { LanguageFilter.keep(it.category_name, AppPreferences.contentLanguages) } else cats

    override suspend fun liveCategories(): List<Category> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        filterCats(StalkerParser.genres(call("type=itv&action=get_genres")))
    }

    override suspend fun liveStreams(categoryId: String?): List<LiveStream> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val all = StalkerParser.channels(call("type=itv&action=get_all_channels")).ifEmpty { crawl() }
        channels = all.associateBy { it.longId }
        val cats = StalkerParser.genres(call("type=itv&action=get_genres"))
        val blocked = if (AppPreferences.englishOnly) LanguageFilter.blockedCategoryIds(cats, AppPreferences.contentLanguages) else emptySet()
        all.asSequence()
            .filter { categoryId == null || it.genreId == categoryId }
            .filter { !AppPreferences.englishOnly || (it.genreId !in blocked && LanguageFilter.keep(it.name, AppPreferences.contentLanguages)) }
            .map {
                LiveStream(
                    num = it.number, name = it.name, streamId = it.longId,
                    icon = StalkerParser.absolute(origin, it.logo), epgId = it.xmltvId.ifBlank { null },
                    categoryId = it.genreId.ifBlank { null },
                )
            }.toList()
    }

    /** Portals without `get_all_channels` page through `get_ordered_list` instead. */
    private fun crawl(): List<StalkerParser.Channel> {
        val seen = LinkedHashMap<String, StalkerParser.Channel>()
        for (page in 1..60) {
            val got = StalkerParser.channels(call("type=itv&action=get_ordered_list&genre=*&sortby=number&p=$page"))
            val before = seen.size
            got.forEach { seen.putIfAbsent(it.id, it) }
            if (got.isEmpty() || seen.size == before) break
        }
        return seen.values.toList()
    }

    override suspend fun shortEpg(streamId: Long, limit: Int): List<EpgEntry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ch = channels[streamId] ?: return@withContext emptyList()
        val js = runCatching { call("type=itv&action=get_short_epg&ch_id=${ch.id}&size=$limit") }.getOrNull()
        (js as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            EpgEntry(title = s("name"), description = s("descr"), startTs = s("start_timestamp"), stopTs = s("stop_timestamp"))
        }
    }

    override suspend fun epgTable(streamId: Long): List<EpgEntry> = shortEpg(streamId, 12)

    @Volatile private var movies: Map<Long, StalkerParser.Movie> = emptyMap()
    @Volatile private var seriesMeta: Map<Long, StalkerParser.SeriesItem> = emptyMap()
    @Volatile private var episodes: Map<String, StalkerParser.Episode> = emptyMap()

    private fun keepVod(name: String, categoryId: String?): Boolean =
        !AppPreferences.englishOnly || LanguageFilter.keep(name, AppPreferences.contentLanguages)

    override suspend fun movieCategories(): List<Category> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        filterCats(StalkerParser.genres(call("type=vod&action=get_categories")))
    }

    override suspend fun vodStreams(categoryId: String?): List<VodStream> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val q = categoryId?.let { "&category=$it" } ?: ""
        val all = StalkerParser.movies(call("type=vod&action=get_items$q"))
            .ifEmpty { pageVod() }
        movies = all.associateBy { it.longId }
        all.asSequence()
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { keepVod(it.name, it.categoryId) }
            .map {
                VodStream(
                    name = it.name, streamId = it.longId,
                    icon = StalkerParser.absolute(origin, it.poster),
                    rating = it.rating, ext = null, categoryId = it.categoryId.ifBlank { null },
                )
            }.toList()
    }

    /** Portals that ignore the category filter page their whole VOD list instead. */
    private fun pageVod(): List<StalkerParser.Movie> {
        val seen = LinkedHashMap<String, StalkerParser.Movie>()
        for (page in 1..30) {
            val got = StalkerParser.movies(call("type=vod&action=get_ordered_list&type=vod&sortby=name&p=$page"))
            got.forEach { seen.putIfAbsent(it.id, it) }
            if (got.isEmpty()) break
        }
        return seen.values.toList()
    }

    override suspend fun vodInfo(vodId: Long): VodInfo? {
        val cached = movies[vodId]
        val js = runCatching { call("type=vod&action=get_info&item_id=${cached?.id ?: vodId}") }.getOrNull()
        val o = js as? kotlinx.serialization.json.JsonObject
        val info = (o?.get("info") as? kotlinx.serialization.json.JsonObject) ?: o
        fun s(k: String) = (info?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
        // Remember the playable command so movie playback can ask the portal for a fresh link.
        val cmd = s("cmd")?.ifBlank { null } ?: s("video_url")?.ifBlank { null }
        if (cmd != null) {
            val m = cached?.copy(cmd = cmd) ?: StalkerParser.Movie(
                id = s("id") ?: vodId.toString(), name = s("name") ?: "", poster = "", cmd = cmd,
                rating = null, categoryId = "",
            )
            movies = movies + (vodId to m)
        }
        return VodInfo(
            name = s("name") ?: cached?.name,
            plot = s("description"),
            director = s("director"),
            genre = s("genre"),
            releasedate = s("releasedate"),
        )
    }

    override suspend fun seriesCategories(): List<Category> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        filterCats(StalkerParser.genres(call("type=series&action=get_categories")))
    }

    override suspend fun series(categoryId: String?): List<Series> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val q = categoryId?.let { "&category=$it" } ?: ""
        val all = StalkerParser.seriesList(call("type=series&action=get_items$q"))
        seriesMeta = all.associateBy { it.longId }
        all.asSequence()
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { keepVod(it.name, it.categoryId) }
            .map {
                Series(
                    seriesId = it.longId, name = it.name,
                    cover = StalkerParser.absolute(origin, it.poster),
                    plot = it.plot.ifBlank { null }, categoryId = it.categoryId.ifBlank { null },
                )
            }.toList()
    }

    override suspend fun seriesEpisodes(seriesId: Long): Map<Int, List<Episode>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sid = seriesMeta[seriesId]?.id ?: seriesId.toString()
        val eps = StalkerParser.episodes(call("type=series&action=get_hierarchy&series_id=$sid"))
        episodes = eps.associateBy { it.id }
        eps.groupBy { it.season }.mapValues { (_, list) ->
            list.map { e ->
                Episode(id = e.id, title = e.name, episodeNum = e.num, ext = null)
            }
        }
    }

    override fun liveUrl(id: Long, hls: Boolean?): String = "$SCHEME://channel/$id"
    override fun timeshiftUrl(id: Long, startMs: Long, endMs: Long, hls: Boolean?): String = liveUrl(id, hls)

    /** Portal playback always resolves inside the player; ext is unused because the portal serves the container. */
    override fun movieUrl(id: Long, ext: String?): String = "$SCHEME://movie/$id"
    override fun episodeUrl(id: String, ext: String?): String = "$SCHEME://episode/${URLEncoder.encode(id, "UTF-8")}"

    /** The real link for a channel. Blocking: call from a loader thread. */
    fun resolveBlocking(id: Long): String {
        val ch = channels[id] ?: throw IOException("Channel list not loaded")
        if (!StalkerParser.needsCreateLink(ch)) return StalkerParser.playable(ch.cmd)
        val cmd = URLEncoder.encode(ch.cmd, "UTF-8")
        val js = call("type=itv&action=create_link&cmd=$cmd&series=&forced_storage=undefined&disable_ad=0&download=0")
        return StalkerParser.linkFrom(js) ?: throw IOException("The portal gave no link for ${ch.name}")
    }

    /** Real link for a portal movie: ask with the movie's command, which the portal must mint a fresh URL from. */
    private fun resolveMovieBlocking(id: Long): String {
        val m = movies[id] ?: throw IOException("Movie list not loaded")
        val cmd = m.cmd.ifBlank {
            val info = runCatching { call("type=vod&action=get_info&item_id=${m.id}") }.getOrNull()
            val o = info as? kotlinx.serialization.json.JsonObject
            val inner = (o?.get("info") as? kotlinx.serialization.json.JsonObject) ?: o
            ((inner?.get("cmd") ?: inner?.get("video_url")) as? kotlinx.serialization.json.JsonPrimitive)?.content
        }?.ifBlank { null } ?: throw IOException("The portal gave no command for ${m.name}")
        val js = call("type=vod&action=create_link&cmd=${URLEncoder.encode(cmd, "UTF-8")}&forced_storage=undefined&disable_ad=0&download=0")
        return StalkerParser.vodLink(js) ?: StalkerParser.playable(cmd).takeIf { it.contains("://") }
            ?: throw IOException("The portal gave no link for ${m.name}")
    }

    /** Real link for a portal episode; series create_link needs the series id alongside the command. */
    private fun resolveEpisodeBlocking(id: String): String {
        val e = episodes[id] ?: throw IOException("Episode list not loaded")
        val js = call("type=series&action=create_link&cmd=${URLEncoder.encode(e.cmd, "UTF-8")}&series=${e.seriesId}&forced_storage=undefined&disable_ad=0&download=0")
        return StalkerParser.vodLink(js) ?: StalkerParser.playable(e.cmd).takeIf { it.contains("://") }
            ?: throw IOException("The portal gave no link for ${e.name}")
    }

    /** Plugged into the player's data source so `stalker://` addresses become real links when playback opens. */
    object Resolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            if (dataSpec.uri.scheme != SCHEME) return dataSpec
            val client = ProviderSession.current as? StalkerClient ?: throw IOException("The portal is no longer active")
            val uri = Uri.parse(dataSpec.uri.toString())
            return when (uri.host) {
                "movie" -> dataSpec.withUri(Uri.parse(client.resolveMovieBlocking(
                    uri.lastPathSegment?.toLongOrNull() ?: throw IOException("Bad movie address"),
                )))
                "episode" -> dataSpec.withUri(Uri.parse(client.resolveEpisodeBlocking(
                    uri.lastPathSegment ?: throw IOException("Bad episode address"),
                )))
                else -> {
                    val id = uri.lastPathSegment?.toLongOrNull() ?: throw IOException("Bad channel address")
                    dataSpec.withUri(Uri.parse(client.resolveBlocking(id)))
                }
            }
        }
    }

    companion object {
        const val SCHEME = "stalker"
    }
}
