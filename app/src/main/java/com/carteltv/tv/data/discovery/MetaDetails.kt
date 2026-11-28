package com.carteltv.tv.data.discovery

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.stremio.StremioAddons
import com.carteltv.tv.data.stremio.StremioClient
import com.carteltv.tv.data.stremio.titleAliases
import com.carteltv.tv.core.suspendRunCatching
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* ── META DETAILS ──────────────────────────────────────────
 * The normalized title page: TMDB metadata enriched with Stremio add-on meta,
 * or the reverse. The library and every detail row read this one shape, so the
 * page works for an IMDb id and for a TMDB id alike.
 * ────────────────────────────────────────────────────────── */

data class MetaCast(val name: String, val character: String?, val photo: String?)

data class MetaCompany(val name: String, val logo: String?)

data class MetaEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val name: String?,
    val overview: String?,
    val still: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
)

data class MetaSeason(val number: Int, val name: String?, val overview: String?, val poster: String?)

data class MetaCollection(val id: String, val name: String, val poster: String?, val backdrop: String?)

/** A related-title tile: the canonical ref plus the art and facts its card shows. */
data class MetaSummary(
    val refId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val year: String?,
    val rating: String?,
)

data class MetaDetails(
    val refId: String,
    val type: String,
    val name: String,
    val originalName: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val releaseDate: String? = null,
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    val votes: Int? = null,
    val certification: String? = null,
    val genres: List<String> = emptyList(),
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val trailerKey: String? = null,
    val cast: List<MetaCast> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val companies: List<MetaCompany> = emptyList(),
    /** The IMDb id every add-on stream is asked for; null when only TMDB knows the title. */
    val playableId: String? = null,
    val seasons: List<MetaSeason> = emptyList(),
    /** Episode rows that Stremio metadata already carried, keyed by season. */
    val stremioEpisodes: Map<Int, List<MetaEpisode>> = emptyMap(),
    val recommendations: List<MetaSummary> = emptyList(),
    val collection: MetaCollection? = null,
) {
    val isSeries: Boolean get() = type == "series"
    val yearLabel: String? get() = year?.takeIf { it.isNotBlank() }
    val runtimeLabel: String? get() = runtimeMinutes?.takeIf { it > 0 }?.let { "${it} min" }

    fun summary(): MetaSummary = MetaSummary(refId, type, name, poster, year, rating?.let { "%.1f".format(it) })

    /** The Stremio meta the stream picker consumes. */
    fun toMetum(): StremioClient.Metum = StremioClient.Metum(
        id = playableId.orEmpty(),
        type = type,
        name = name,
        poster = poster,
        background = backdrop,
        description = overview,
        releaseInfo = year,
        imdbRating = rating?.let { "%.1f".format(it) },
        genres = genres,
        cast = cast.map { it.name },
        director = directors,
        runtime = runtimeLabel,
    )
}

object MetaRepository {

    private const val CACHE_SIZE = 40
    private val cache = object : LinkedHashMap<String, MetaDetails>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MetaDetails>?): Boolean =
            size > CACHE_SIZE
    }

    /** "tmdb:movie:123" → (movie, 123, true); "tt123" → (movie?, tt123, false). */
    fun refOf(type: String, rawId: String): String =
        if (rawId.startsWith(TMDB_PREFIX)) rawId else "stremio:$rawId"

    fun splitRef(type: String, rawId: String): Triple<String, String, Boolean> {
        val tmdb = rawId.removePrefix(TMDB_PREFIX).split(':')
        return if (rawId.startsWith(TMDB_PREFIX) && tmdb.size == 2) {
            Triple(tmdb[0].ifBlank { type }, tmdb[1], true)
        } else {
            Triple(type, rawId, false)
        }
    }

    suspend fun load(type: String, rawId: String, fallbackName: String? = null, fallbackPoster: String? = null): MetaDetails? {
        val (mediaType, id, viaTmdb) = splitRef(type, rawId)
        val cacheKey = "$mediaType:$id"
        synchronized(cache) { cache[cacheKey] }?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            if (viaTmdb) fromTmdb(mediaType, id, fallbackName, fallbackPoster)
            else fromStremio(mediaType, id, fallbackName, fallbackPoster)
        } ?: return null
        synchronized(cache) { cache[cacheKey] = loaded }
        return loaded
    }

    /** One season's episodes, TMDB stills and Stremio ids merged by episode number. */
    suspend fun season(details: MetaDetails, season: Int): List<MetaEpisode> = withContext(Dispatchers.IO) {
        val tmdbId = details.refId.takeIf { it.startsWith(TMDB_PREFIX) }?.removePrefix(TMDB_PREFIX)?.substringAfter(':')?.toIntOrNull()
            ?: details.playableId?.let { suspendRunCatching { TmdbTitles.forImdb(it, details.isSeries)?.tmdbId }.getOrNull() }
        val fromTmdb = tmdbId?.let { id -> tmdbSeasons(if (details.isSeries) "tv" else "movie", id, details.playableId, season) }.orEmpty()
        val fromAddons = details.stremioEpisodes[season].orEmpty()
        (fromAddons + fromTmdb).groupBy { it.episode }.map { (_, copies) ->
            copies.firstOrNull { it.still != null || it.overview != null } ?: copies.first()
        }.sortedBy { it.episode }
    }

    private suspend fun fromStremio(
        type: String,
        imdbId: String,
        fallbackName: String?,
        fallbackPoster: String?,
    ): MetaDetails? {
        if (!imdbId.startsWith("tt")) return null
        val meta = StremioAddons.meta(type, imdbId)
        val tmdb = suspendRunCatching { TmdbTitles.forImdb(imdbId, type == "series") }.getOrNull()?.tmdbId
        val rich = tmdb?.let { fromTmdb(type, it.toString(), fallbackName ?: meta?.name, fallbackPoster) }
        val videos = meta?.videos.orEmpty()
        val episodes = videos.filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
            .groupBy { it.season ?: 0 }
            .mapValues { (_, rows) ->
                rows.map {
                    MetaEpisode(
                        id = it.id.ifBlank { "$imdbId:${it.season}:${it.episode}" },
                        season = it.season ?: 0,
                        episode = it.episode ?: 0,
                        name = it.title,
                        overview = it.overview,
                        still = it.thumbnail,
                        airDate = it.released,
                        runtimeMinutes = null,
                    )
                }
            }
        val base = rich ?: MetaDetails(
            refId = "stremio:$imdbId",
            type = type,
            name = meta?.name ?: fallbackName ?: imdbId,
            originalName = meta?.name,
            overview = meta?.description,
            year = meta?.releaseInfo,
            rating = meta?.imdbRating?.toDoubleOrNull(),
            genres = meta?.genres.orEmpty(),
            poster = meta?.poster ?: fallbackPoster,
            backdrop = meta?.background,
            logo = meta?.logo,
            cast = meta?.cast.orEmpty().map { MetaCast(it, null, null) },
            directors = meta?.director.orEmpty(),
            runtimeMinutes = meta?.runtime?.filter { it.isDigit() }?.toIntOrNull(),
            playableId = imdbId,
        )
        return base.copy(
            refId = "stremio:$imdbId",
            playableId = imdbId,
            poster = base.poster ?: meta?.poster ?: fallbackPoster,
            backdrop = base.backdrop ?: meta?.background,
            logo = base.logo ?: meta?.logo,
            stremioEpisodes = episodes,
            name = base.name.takeIf { it.isNotBlank() } ?: (meta?.name ?: imdbId),
        )
    }

    private suspend fun fromTmdb(
        type: String,
        tmdbId: String,
        fallbackName: String?,
        fallbackPoster: String?,
    ): MetaDetails? {
        val isSeries = type == "series" || type == "tv"
        val media = if (isSeries) "tv" else "movie"
        val root = tmdbGet("$media/$tmdbId?append_to_response=videos,credits,recommendations,similar,external_ids,release_dates,content_ratings,belongs_to_collection")
            ?: return null
        fun obj(key: String) = root[key] as? JsonObject
        fun text(key: String) = root.str(key)
        fun int(key: String) = text(key)?.toIntOrNull()
        val imdb = (obj("external_ids")?.str("imdb_id"))?.takeIf { it.startsWith("tt") }
        val date = text(if (isSeries) "first_air_date" else "release_date")
        val rating = text("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 }
        val videos = (obj("videos")?.get("results") as? JsonArray).orEmpty()
        val trailer = videos.mapNotNull { it as? JsonObject }
            .filter { it.str("site").equals("YouTube", true) }
            .let { rows -> rows.firstOrNull { it.str("type") == "Trailer" } ?: rows.firstOrNull { it.str("type") == "Teaser" } }
            ?.str("key")
        val credits = obj("credits")
        val cast = (credits?.get("cast") as? JsonArray).orEmpty().take(24).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            MetaCast(name, o.str("character"), o.str("profile_path")?.let { "https://image.tmdb.org/t/p/w185$it" })
        }
        val crew = (credits?.get("crew") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val directors = crew.filter { it.str("job") == "Director" }.mapNotNull { it.str("name") }.distinct()
        val writers = crew.filter { it.str("job") in setOf("Writer", "Screenplay", "Teleplay", "Story") }.mapNotNull { it.str("name") }.distinct()
        val cert = if (isSeries) certification((obj("content_ratings")?.get("results") as? JsonArray).orEmpty())
        else certification((obj("release_dates")?.get("results") as? JsonArray).orEmpty())
        val similar = ((obj("recommendations")?.get("results") ?: obj("similar")?.get("results")) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { row -> summary(row, media).takeIf { it.name.isNotBlank() } }
            .distinctBy { it.refId }
            .take(18)
        val collection = obj("belongs_to_collection")?.let { c ->
            c.str("id")?.let { id ->
                MetaCollection(
                    id = id,
                    name = c.str("name").orEmpty(),
                    poster = c.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
                    backdrop = c.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
                )
            }
        }
        val seasons = (root["seasons"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val number = o.str("season_number")?.toIntOrNull() ?: return@mapNotNull null
            if (number <= 0 || (o.str("episode_count")?.toIntOrNull() ?: 0) <= 0) return@mapNotNull null
            MetaSeason(number, o.str("name"), o.str("overview"), o.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" })
        }
        val companies = ((root["production_companies"] as? JsonArray).orEmpty()).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            MetaCompany(name, o.str("logo_path")?.let { "https://image.tmdb.org/t/p/w154$it" })
        }
        return MetaDetails(
            refId = "$TMDB_PREFIX$media:$tmdbId",
            type = if (isSeries) "series" else "movie",
            name = text(if (isSeries) "name" else "title") ?: fallbackName ?: tmdbId,
            originalName = text(if (isSeries) "original_name" else "original_title"),
            overview = text("overview"),
            year = date?.take(4)?.takeIf { it.length == 4 },
            releaseDate = date,
            runtimeMinutes = if (isSeries) (root["episode_run_time"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            else int("runtime"),
            rating = rating,
            votes = int("vote_count"),
            certification = cert,
            genres = (root["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") },
            poster = text("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" } ?: fallbackPoster,
            backdrop = text("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
            trailerKey = trailer,
            cast = cast,
            directors = directors,
            writers = writers,
            companies = companies,
            playableId = imdb,
            seasons = seasons.sortedBy { it.number },
            recommendations = similar,
            collection = collection,
        )
    }

    private fun summary(row: JsonObject, media: String): MetaSummary {
        val type = (row.str("media_type") ?: media).let { if (it == "tv") "series" else "movie" }
        val id = row.str("id").orEmpty()
        return MetaSummary(
            refId = "$TMDB_PREFIX${if (type == "series") "tv" else "movie"}:$id",
            type = type,
            name = (row.str("name") ?: row.str("title")).orEmpty(),
            poster = row.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
            year = (row.str("first_air_date") ?: row.str("release_date"))?.take(4),
            rating = row.str("vote_average")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(it) },
        )
    }

    private fun certification(rows: List<kotlinx.serialization.json.JsonElement>): String? {
        val entries = rows.mapNotNull { it as? JsonObject }
        // Prefer a US certificate, then any non-empty one, then a TV rating.
        entries.firstOrNull { it.str("iso_3166_1") == "US" }?.let { us ->
            (us["release_dates"] as? JsonArray)?.firstNotNullOfOrNull { el ->
                (el as? JsonObject)?.str("certification")?.takeIf { c -> c.isNotBlank() }
            }?.let { return it }
            us.str("rating")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return entries.firstNotNullOfOrNull { row ->
            (row["release_dates"] as? JsonArray)?.firstNotNullOfOrNull { el ->
                (el as? JsonObject)?.str("certification")?.takeIf { c -> c.isNotBlank() }
            } ?: row.str("rating")?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun tmdbSeasons(media: String, tmdbId: Int, playableId: String?, season: Int): List<MetaEpisode> {
        val root = tmdbGet("$media/$tmdbId/season/$season") ?: return emptyList()
        return (root["episodes"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val number = o.str("episode_number")?.toIntOrNull() ?: return@mapNotNull null
            MetaEpisode(
                id = playableId?.let { "$it:$season:$number" } ?: "$tmdbId:$season:$number",
                season = season,
                episode = number,
                name = o.str("name"),
                overview = o.str("overview"),
                still = o.str("still_path")?.let { "https://image.tmdb.org/t/p/w300$it" },
                airDate = o.str("air_date"),
                runtimeMinutes = o.str("runtime")?.toIntOrNull(),
            )
        }
    }

    /** The other films in a TMDB collection, for the "Part of" shelf. */
    suspend fun collectionParts(collectionId: String): List<MetaSummary> = withContext(Dispatchers.IO) {
        val root = tmdbGet("collection/$collectionId") ?: return@withContext emptyList()
        (root["parts"] as? JsonArray).orEmpty().mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            summary(row, "movie").takeIf { it.name.isNotBlank() }
        }.sortedBy { it.year }
    }

    private suspend fun tmdbGet(path: String): JsonObject? {        val key = AppPreferences.tmdbKey.trim()
        if (key.isBlank()) return null
        val (base, headers) = DiscoveryFeeds.tmdbRequest(key, path)
        val joiner = if ('?' in base) '&' else '?'
        val body = suspendRunCatching {
            HttpSupport.get("$base${joiner}language=${Locale.getDefault().toLanguageTag()}", headers)
        }.getOrNull() ?: return null
        return runCatching { HttpSupport.lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }

    const val TMDB_PREFIX = "tmdb:"

    /** The aliases an add-on knows for a title, for localized release matching. */
    suspend fun aliases(details: MetaDetails): List<String> {
        val id = details.playableId ?: return emptyList()
        return StremioAddons.meta(details.type, id)?.titleAliases().orEmpty()
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
