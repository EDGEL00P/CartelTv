package com.carteltv.tv.data.discovery

import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CONTENT CLASSIFICATIONS — age ratings for movies and TV, looked up on
 * TMDB (the key the app already vaults for discovery) and labelled with
 * the rating board of the viewer's region.
 *
 * Row law applies: silent when no key is set or the title misses, so the
 * detail screens ship invisible until fed.
 */
object Classifications {

    /** A regional rating: which board issued it, the code, and what it means. */
    data class Rating(val board: String, val code: String, val meaning: String)

    private val json get() = com.carteltv.tv.data.network.HttpSupport.lenientJson

    private fun tmdbRequest(key: String, path: String) = DiscoveryFeeds.tmdbRequest(key, path)

    /** MPAA (US) movie codes → plain meaning. */
    private val MOVIE_MEANINGS = mapOf(
        "G" to "General audiences",
        "PG" to "Parental guidance suggested",
        "PG-13" to "Parents strongly cautioned",
        "R" to "Restricted — under 17 needs an adult",
        "NC-17" to "Adults only",
        "NR" to "Not rated",
    )

    /** US television codes → plain meaning. */
    private val TV_MEANINGS = mapOf(
        "TV-Y" to "All children",
        "TV-Y7" to "Ages 7 and up",
        "TV-G" to "General audiences",
        "TV-PG" to "Parental guidance suggested",
        "TV-14" to "Parents strongly cautioned",
        "TV-MA" to "Mature audiences only",
        "TV-NR" to "Not rated",
    )

    /** The rating board behind each ISO country code we can name. */
    private val BOARDS = mapOf(
        "US" to "MPAA", "GB" to "BBFC", "DE" to "FSK", "FR" to "CNC",
        "AU" to "ACB", "BR" to "DJCTQ", "CA" to "CTC", "IN" to "CBFC",
        "MX" to "RTC", "JP" to "EIRIN", "KR" to "KMRB", "ES" to "ICAA",
        "IT" to "SIAE", "NL" to "Kijkwijzer", "SE" to "SM", "NZ" to "OFLC",
        "IE" to "IFCO", "NO" to "Medierådet", "FI" to "Kavi",
    )

    /** The viewer's region: their watch-country setting, else the system locale, else US. */
    private fun region(): String =
        AppPreferences.watchCountry.ifBlank {
            java.util.Locale.getDefault().country.ifBlank { "US" }
        }.uppercase()

    /**
     * Look up the classification for a title.
     * @param media "movie" or "tv"
     * @param year optional release year, disambiguates remakes when searching
     */
    suspend fun forTitle(media: String, title: String, year: String? = null): Rating? =
        withContext(Dispatchers.IO) {
            val key = AppPreferences.tmdbKey.trim()
            if (key.isBlank() || title.isBlank()) return@withContext null
            runCatching {
                val region = region()
                val yearParam = when {
                    year.isNullOrBlank() -> ""
                    media == "tv" -> "&first_air_date_year=$year"
                    else -> "&primary_release_year=$year"
                }
                val (searchUrl, searchHeaders) = tmdbRequest(
                    key,
                    "search/$media?query=${java.net.URLEncoder.encode(title, "UTF-8")}$yearParam&include_adult=false",
                )
                val id = json.parseToJsonElement(com.carteltv.tv.data.network.HttpSupport.getBlocking(searchUrl, searchHeaders))
                    .jsonObject["results"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return@withContext null

                val path = if (media == "tv") "tv/$id/content_ratings" else "movie/$id/release_dates"
                val (url, headers) = tmdbRequest(key, path)
                val results = json.parseToJsonElement(com.carteltv.tv.data.network.HttpSupport.getBlocking(url, headers))
                    .jsonObject["results"]?.jsonArray ?: return@withContext null

                // movies: results are per-country groups of release dates carrying certifications
                val byRegion = mutableMapOf<String, String>()
                for (r in results) {
                    val o = r.jsonObject
                    val iso = o["iso_3166_1"]?.jsonPrimitive?.content ?: continue
                    val code = if (media == "tv") {
                        o["rating"]?.jsonPrimitive?.content
                    } else {
                        o["release_dates"]?.jsonArray
                            ?.mapNotNull { it.jsonObject["certification"]?.jsonPrimitive?.content }
                            ?.firstOrNull { it.isNotBlank() }
                    } ?: continue
                    if (code.isNotBlank()) byRegion[iso] = code
                }

                val meanings = if (media == "tv") TV_MEANINGS else MOVIE_MEANINGS
                // The viewer's board first, then the US one every streamer recognises, then anything.
                val code = byRegion[region]
                    ?: byRegion["US"]
                    ?: byRegion.values.firstOrNull()
                    ?: return@withContext null
                val boardRegion = byRegion[region]?.let { region }
                    ?: (byRegion["US"]?.let { "US" } ?: byRegion.keys.firstOrNull() ?: region)
                Rating(
                    board = BOARDS[boardRegion] ?: boardRegion,
                    code = code,
                    meaning = meanings[code].orEmpty(),
                )
            }.getOrNull()
        }
}
