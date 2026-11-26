package com.carteltv.tv.data.chapters

import com.carteltv.tv.engine.chapters.ChapterSkip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parses Cinemeta/Stremio playback refs and IntroDB segment JSON. */
object IntroSkipDatabase {

    /** What the player knows about the title: the IMDb id, plus season/episode for TV. */
    data class SkipRef(val imdbId: String, val season: Int? = null, val episode: Int? = null)

    private val IMDB_ID = Regex("^tt\\d{7,8}$")
    private val SERIES_REF = Regex("^series:(\\S+)/(-?\\d+):(-?\\d+)$")

    /**
     * Derives the identity from the playback refId. Stremio playback passes
     * `stremio:<metaId>` for movies and `series:<metaId>/<season>:<episode>` for episodes, where
     * Cinemeta ids are IMDb ids. Anything else (Xtream `movie:`/`live:`, YouTube `yt:`, custom
     * add-on ids) returns null: without a true IMDb id there is nothing safe to query.
     */
    fun parseRefId(refId: String): SkipRef? {
        if (refId.startsWith("stremio:")) {
            val id = refId.removePrefix("stremio:")
            return if (IMDB_ID.matches(id)) SkipRef(id) else null
        }
        val m = SERIES_REF.matchEntire(refId) ?: return null
        val (id, season, episode) = m.destructured
        if (!IMDB_ID.matches(id)) return null
        val s = season.toIntOrNull() ?: return null
        val e = episode.toIntOrNull() ?: return null
        return SkipRef(id, s, e)
    }

    /** Maps a /segments response onto skip segments; null segments mean "not timed" and are dropped. */
    fun parse(json: String): List<ChapterSkip.Segment> {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ChapterSkip.Segment>()
        for ((key, kind) in KINDS) {
            val seg = root[key] as? JsonObject ?: continue
            val start = seg["start_ms"]?.jsonPrimitive?.longOrNull ?: continue
            val end = seg["end_ms"]?.jsonPrimitive?.longOrNull ?: continue
            if (end - start in MIN_MS..MAX_MS) out += ChapterSkip.Segment(kind, start, end)
        }
        return out
    }

    private val KINDS = mapOf(
        "intro" to ChapterSkip.Kind.INTRO,
        "recap" to ChapterSkip.Kind.RECAP,
        "outro" to ChapterSkip.Kind.CREDITS,
        // post_credits is deliberately unmapped: ChapterSkip never offers it.
    )

    private const val MIN_MS = 5_000L
    private const val MAX_MS = 900_000L
}
