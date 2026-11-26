package com.carteltv.tv.data.stremio

import com.carteltv.tv.data.chapters.IntroSkipDatabase
import java.util.concurrent.ConcurrentHashMap

/** What the player shows about a title, fetched from Cinemeta by IMDb id. */
data class OnlineTitleInfo(
    val name: String,
    val year: String?,
    val rating: String?,
    val runtime: String?,
    val genres: List<String>,
    val overview: String?,
    val episodeTitle: String?,
    val episodeOverview: String?,
    val cast: List<String>,
    val director: List<String>,
    val poster: String?,
    val episodeThumb: String?,
)

object OnlineTitleInfoLoader {
    private val cache = ConcurrentHashMap<String, OnlineTitleInfo>()

    /** Null when [refId] has no IMDb identity (live, Xtream, YouTube) or Cinemeta has no entry. */
    suspend fun load(refId: String): OnlineTitleInfo? {
        val ref = IntroSkipDatabase.parseRefId(refId) ?: return null
        cache[refId]?.let { return it }
        val type = if (ref.season != null) "series" else "movie"
        val meta = StremioClient(StremioClient.CINEMETA).meta(type, ref.imdbId) ?: return null
        val episode = meta.videos.firstOrNull { it.season == ref.season && it.episode == ref.episode }
        val info = OnlineTitleInfo(
            name = meta.name,
            year = meta.releaseInfo?.takeIf { it.isNotBlank() },
            rating = meta.imdbRating?.takeIf { it.isNotBlank() },
            runtime = meta.runtime?.takeIf { it.isNotBlank() },
            genres = meta.genres.take(3),
            overview = meta.description?.takeIf { it.isNotBlank() },
            episodeTitle = episode?.title?.takeIf { it.isNotBlank() },
            episodeOverview = episode?.overview?.takeIf { it.isNotBlank() },
            cast = meta.cast.take(5),
            director = meta.director.take(2),
            poster = meta.poster?.takeIf { it.isNotBlank() },
            episodeThumb = episode?.thumbnail?.takeIf { it.isNotBlank() },
        )
        cache[refId] = info
        return info
    }
}
