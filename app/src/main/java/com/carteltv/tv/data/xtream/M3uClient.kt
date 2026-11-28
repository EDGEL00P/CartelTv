package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.network.OkHttpTransport
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.provider.ProviderType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An M3U playlist presented through the same surface as an Xtream server, so every screen works unchanged.
 * Channels, movies and series are told apart by link shape and name (see [M3uParser.kindOf]). The playlist is
 * downloaded once and reused for [TTL_MS]; the guide comes from the playlist's own `url-tvg` header or from the
 * guide links the user adds.
 */
class M3uClient(private val playlistUrl: String) : XtreamClient(playlistUrl, "", "") {
    override val kind: ProviderType get() = ProviderType.M3U

    private class Loaded(
        val live: List<M3uParser.Entry>,
        val movies: List<M3uParser.Entry>,
        val shows: Map<String, List<M3uParser.Episode>>,
        val guides: List<String>,
        @Volatile var at: Long,
        /** The ETag or Last-Modified the server gave this download (see [ProviderUrlValidator]); blank when it gave none. */
        val validator: String,
    ) {
        val urls = HashMap<Long, String>()
        val catchup = HashMap<Long, M3uParser.Entry>()
        val byUrl = HashMap<String, M3uParser.Entry>()
        val showIds = shows.keys.associateBy { M3uParser.idOf("series:$it") }
    }

    @Volatile private var loaded: Loaded? = null
    private val gate = Mutex()

    private suspend fun playlist(force: Boolean = false): Loaded = gate.withLock {
        val cur = loaded
        val now = System.currentTimeMillis()
        if (!force && cur != null && now - cur.at < TTL_MS) return cur
        val result = download(if (force) "" else cur?.validator.orEmpty())
        val parsed = result.playlist
        if (parsed == null) {
            // 304: the stored playlist is still current, so it is not downloaded or parsed again.
            val c = requireNotNull(cur)
            c.at = now
            return c
        }
        val live = ArrayList<M3uParser.Entry>()
        val movies = ArrayList<M3uParser.Entry>()
        val shows = LinkedHashMap<String, MutableList<M3uParser.Episode>>()
        for (e in parsed.entries) when (M3uParser.kindOf(e)) {
            M3uParser.Kind.LIVE -> live += e
            M3uParser.Kind.MOVIE -> movies += e
            M3uParser.Kind.SERIES -> M3uParser.episodeOf(e)?.let { (show, ep) -> shows.getOrPut(show) { ArrayList() } += ep } ?: run { movies += e }
        }
        Loaded(live, movies, shows, parsed.guideUrls, now, result.validator).also { l ->
            (live + movies).forEach { l.urls[M3uParser.idOf(it.url)] = it.url }
            live.forEach { if (it.catchupSource.isNotBlank()) l.catchup[M3uParser.idOf(it.url)] = it }
            shows.values.flatten().forEach { l.urls[M3uParser.idOf(it.entry.url)] = it.entry.url }
            (live + movies + shows.values.flatten().map { it.entry }).forEach {
                l.byUrl[it.url] = it
                l.byUrl.putIfAbsent(it.url.substringBefore('?'), it)
            }
            loaded = l
        }
    }

    /** [playlist] is null when the server answered 304 Not Modified. */
    private class Download(val playlist: M3uParser.Playlist?, val validator: String)

    private suspend fun download(known: String): Download {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = OkHttpTransport.USER_AGENT
        ProviderUrlValidator.requestHeader(known)?.let { (name, value) -> headers[name] = value }

        return try {
            HttpSupport.getStream(playlistUrl, headers, connectMs = 15_000, readMs = 60_000).use { stream ->
                if (stream.code == 304) {
                if (known.isBlank()) throw IllegalStateException("Playlist answered 304 before a cache validator was available")
                return Download(null, known)
            }
                if (stream.code !in 200..299) throw IllegalStateException("Playlist answered HTTP ${stream.code}")

                val validator = ProviderUrlValidator.of(
                    stream.headers["ETag"]?.firstOrNull(),
                    stream.headers["Last-Modified"]?.firstOrNull(),
                )

                // OkHttp transparently decompresses gzip; the limited reader caps the decoded playlist.
                val input = LimitedInputStream(stream.body, MAX_BYTES)
                val playlist = input.bufferedReader(Charsets.UTF_8).use { M3uParser.parse(it.lineSequence()) }
                Download(playlist, validator)
            }
        } catch (e: java.io.IOException) {
            throw java.io.IOException(HttpSupport.redact(e.message).ifBlank { "Could not download the playlist" })
        }
    }

    override suspend fun authenticate(): Pair<UserInfo?, ServerInfo?> {
        val p = playlist(force = true)
        if (p.live.isEmpty() && p.movies.isEmpty() && p.shows.isEmpty()) {
            throw IllegalStateException("No channels found. Is this an M3U playlist link?")
        }
        return null to null
    }

    /** Only asks the server for the headers, so a health check never downloads the whole playlist. */
    override suspend fun ping() {
        val (code, _, _) = HttpSupport.requestFull(
            playlistUrl, "HEAD",
            mapOf("User-Agent" to OkHttpTransport.USER_AGENT),
            connectMs = 10_000, readMs = 10_000,
        )
        if (code == -1 || code !in 200..299) throw IllegalStateException("Playlist answered HTTP $code")
    }

    override fun defaultGuideUrl(): String = loaded?.guides?.firstOrNull().orEmpty()

    private fun categories(entries: Collection<String>): List<Category> =
        entries.filter { it.isNotBlank() }.distinct().map { Category(it, it) }

    private fun visible(cats: List<Category>): List<Category> =
        if (AppPreferences.englishOnly) cats.filter { LanguageFilter.keep(it.category_name, AppPreferences.contentLanguages) } else cats

    private fun blocked(cats: List<Category>): Set<String> =
        if (AppPreferences.englishOnly) LanguageFilter.blockedCategoryIds(cats, AppPreferences.contentLanguages) else emptySet()

    private fun keep(name: String, cat: String?, blocked: Set<String>) =
        !AppPreferences.englishOnly || (cat !in blocked && LanguageFilter.keep(name, AppPreferences.contentLanguages))

    override suspend fun liveCategories(): List<Category> =
        visible(categories(playlist().live.map { it.group }))

    override suspend fun liveStreams(categoryId: String?): List<LiveStream> {
        val p = playlist()
        val block = blocked(categories(p.live.map { it.group }))
        return p.live.asSequence()
            .filter { categoryId == null || it.group == categoryId }
            .filter { keep(it.name, it.group.ifBlank { null }, block) }
            .mapIndexed { i, e ->
                LiveStream(
                    num = i + 1, name = e.name, streamId = M3uParser.idOf(e.url),
                    icon = e.logo.ifBlank { null }, epgId = e.tvgId.ifBlank { null },
                    categoryId = e.group.ifBlank { null },
                )
            }.toList()
    }

    override suspend fun shortEpg(streamId: Long, limit: Int): List<EpgEntry> = emptyList()
    override suspend fun epgTable(streamId: Long): List<EpgEntry> = emptyList()

    override suspend fun movieCategories(): List<Category> =
        visible(categories(playlist().movies.map { it.group }))

    override suspend fun vodStreams(categoryId: String?): List<VodStream> {
        val p = playlist()
        val block = blocked(categories(p.movies.map { it.group }))
        return p.movies.asSequence()
            .filter { categoryId == null || it.group == categoryId }
            .filter { keep(it.name, it.group.ifBlank { null }, block) }
            .map { e ->
                VodStream(
                    name = e.name, streamId = M3uParser.idOf(e.url), icon = e.logo.ifBlank { null },
                    ext = e.url.substringBefore('?').substringAfterLast('.', "mp4").take(5).ifBlank { "mp4" },
                    categoryId = e.group.ifBlank { null },
                )
            }.toList()
    }

    override suspend fun vodInfo(vodId: Long): VodInfo? {
        val e = playlist().movies.firstOrNull { M3uParser.idOf(it.url) == vodId } ?: return null
        return VodInfo(name = e.name, genre = e.group.ifBlank { null })
    }

    override suspend fun seriesCategories(): List<Category> =
        visible(categories(playlist().shows.values.flatten().map { it.entry.group }))

    override suspend fun series(categoryId: String?): List<Series> {
        val p = playlist()
        return p.shows.entries.asSequence()
            .filter { (_, eps) -> categoryId == null || eps.first().entry.group == categoryId }
            .filter { (name, eps) -> keep(name, eps.first().entry.group.ifBlank { null }, emptySet()) }
            .map { (name, eps) ->
                Series(
                    seriesId = M3uParser.idOf("series:$name"), name = name,
                    cover = eps.first().entry.logo.ifBlank { null },
                    categoryId = eps.first().entry.group.ifBlank { null },
                )
            }.toList()
    }

    override suspend fun seriesEpisodes(seriesId: Long): Map<Int, List<Episode>> {
        val p = playlist()
        val name = p.showIds[seriesId] ?: return emptyMap()
        return p.shows[name].orEmpty()
            .sortedWith(compareBy({ it.season }, { it.number }))
            .groupBy({ it.season }) {
                Episode(
                    id = M3uParser.idOf(it.entry.url).toString(), title = it.title,
                    episodeNum = it.number, ext = it.entry.url.substringBefore('?').substringAfterLast('.', "mp4").take(5),
                )
            }
    }

    private fun urlFor(id: Long): String = loaded?.urls?.get(id).orEmpty()

    override fun liveUrl(id: Long, hls: Boolean?): String = urlFor(id)

    override fun timeshiftUrl(id: Long, startMs: Long, endMs: Long, hls: Boolean?): String {
        val live = urlFor(id)
        val e = loaded?.catchup?.get(id) ?: return live
        return M3uParser.catchupUrl(e.catchupSource, live, startMs, endMs)
    }

    override fun movieUrl(id: Long, ext: String?): String = urlFor(id)

    override fun streamMeta(url: String): StreamMetadata? {
        val l = loaded ?: return null
        // A provider switch may have replaced the playlist since the URL was produced: a miss
        // simply plays without headers, never with another channel's headers.
        val e = l.byUrl[url] ?: l.byUrl[url.substringBefore('?')] ?: return null
        if (e.headers.isEmpty() && e.drm == null) return null
        return StreamMetadata(e.headers, e.drm)
    }
    override fun episodeUrl(id: String, ext: String?): String = urlFor(id.toLongOrNull() ?: return "")

    companion object {
        private const val TTL_MS = 10 * 60_000L
        /** Largest playlist read, before or after gzip; a hostile or broken link must not exhaust a low-RAM TV. */
        private const val MAX_BYTES = 400_000_000L
    }
}
