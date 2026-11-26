package com.carteltv.tv.data.scrape

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.stremio.matchesSeries
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun parseRoot(body: String): JsonElement? = runCatching { HttpSupport.lenientJson.parseToJsonElement(body) }.getOrNull()
private fun JsonElement?.obj(key: String): JsonElement? = (this as? JsonObject)?.get(key)
private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.content
private fun JsonElement?.int(): Int = text()?.toIntOrNull() ?: 0
private fun JsonElement?.long(): Long = text()?.toLongOrNull() ?: 0L

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
private fun episodeTag(season: Int, episode: Int) = "S%02dE%02d".format(season, episode)

object YtsSource : ScrapeSource {
    override val id = "YTS"
    internal val MIRRORS = listOf("https://yts.mx", "https://yts.lt", "https://yts.am")

    override fun applies(request: ScrapeRequest) = !request.isSeries

    override suspend fun search(request: ScrapeRequest): List<Release> =
        parse(MirrorHttp.get(id, MIRRORS, "/api/v2/list_movies.json?query_term=${encode(request.imdbId)}&limit=5"))

    internal fun parse(body: String): List<Release> {
        val movies = parseRoot(body).obj("data").obj("movies") as? JsonArray ?: return emptyList()
        return movies.flatMap { movie ->
            val title = movie.obj("title").text().orEmpty()
            val year = movie.obj("year").text().orEmpty()
            (movie.obj("torrents") as? JsonArray).orEmpty().mapNotNull { t ->
                val hash = cleanHash(t.obj("hash").text()) ?: return@mapNotNull null
                val name = listOf(title, year, t.obj("quality").text(), t.obj("type").text(), t.obj("video_codec").text())
                    .filterNot { it.isNullOrBlank() }.joinToString(" ")
                Release(hash, name, t.obj("seeds").int(), t.obj("size_bytes").long(), id)
            }
        }
    }
}

object EztvSource : ScrapeSource {
    override val id = "EZTV"
    internal val MIRRORS = listOf("https://eztvx.to", "https://eztv.re", "https://eztv.wf", "https://eztv.tf", "https://eztv.yt")
    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 3

    override fun applies(request: ScrapeRequest) = request.isSeries

    override suspend fun search(request: ScrapeRequest): List<Release> {
        val imdb = request.imdbId.removePrefix("tt")
        val out = mutableListOf<Release>()
        for (page in 1..MAX_PAGES) {
            val batch = parse(MirrorHttp.get(id, MIRRORS, "/api/get-torrents?imdb_id=$imdb&limit=$PAGE_SIZE&page=$page"))
            out += batch
            val found = batch.any { matchesSeries(request.title, it.name, request.season, request.episode, request.aliases) }
            if (found || batch.size < PAGE_SIZE) break
        }
        return out
    }

    internal fun parse(body: String): List<Release> {
        val torrents = parseRoot(body).obj("torrents") as? JsonArray ?: return emptyList()
        return torrents.mapNotNull { t ->
            val hash = cleanHash(t.obj("hash").text()) ?: return@mapNotNull null
            val name = (t.obj("title").text() ?: t.obj("filename").text()).orEmpty()
            Release(hash, name, t.obj("seeds").int(), t.obj("size_bytes").long(), id)
        }
    }
}

object PirateBaySource : ScrapeSource {
    override val id = "TPB"
    internal val MIRRORS = listOf("https://apibay.org")

    override fun applies(request: ScrapeRequest) = true

    override suspend fun search(request: ScrapeRequest): List<Release> {
        val query = if (request.isSeries && request.season != null && request.episode != null) {
            "${request.title} ${episodeTag(request.season, request.episode)}"
        } else {
            listOfNotNull(request.title, request.year).joinToString(" ")
        }
        return parse(MirrorHttp.get(id, MIRRORS, "/q.php?q=${encode(query)}&cat=200"))
    }

    internal fun parse(body: String): List<Release> {
        val rows = parseRoot(body) as? JsonArray ?: return emptyList()
        return rows.mapNotNull { r ->
            val hash = cleanHash(r.obj("info_hash").text()) ?: return@mapNotNull null
            Release(hash, r.obj("name").text().orEmpty(), r.obj("seeders").int(), r.obj("size").long(), id)
        }
    }
}
