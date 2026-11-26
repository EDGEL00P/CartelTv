package com.carteltv.tv.data.espn

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.serialization.Serializable

/**
 * Remote data source for ESPN's public scoreboard API. It only fetches and maps; caching, sharing requests and
 * snapshots belong to [ScoreboardRepository], and parsing to [EspnScoreboardMapper].
 */
class EspnScoreboardClient : ScoreboardSource {
    @Serializable data class Score(
        val name: String, val detail: String, val state: String,
        val logoA: String?, val abbrA: String?, val scoreA: String?,
        val logoB: String?, val abbrB: String?, val scoreB: String?,
        val nameA: String? = null, val nameB: String? = null,
        val recordA: String? = null, val recordB: String? = null,
        val startMs: Long = 0L, val network: String? = null, val leagueLogo: String? = null,
        /** Every broadcaster the source lists for the game, [network] first; sources disagree on who airs what. */
        val networks: List<String> = emptyList(),
        /** The ground ("Lenovo Center") and where it is ("Raleigh, NC"), when the feed says. */
        val venue: String? = null,
        val location: String? = null,
        /** True for an arena or roofed ground, where the weather does not matter; null when the feed does not say. */
        val indoor: Boolean? = null,
        /** Period/half/quarter number (0 before the start) and the clock exactly as the feed shows it ("5:32", "67'"). */
        val period: Int = 0,
        val clock: String? = null,
        /** The feed's own one-line status: "End of 1st", "Halftime", "Final", "FT", "3/17 - 1:30 AM EDT". */
        val shortDetail: String? = null,
        /** Each team's brand colours as the feed gives them: six hex digits, no "#". A is the home side, B the away side. */
        val colorA: String? = null, val altColorA: String? = null,
        val colorB: String? = null, val altColorB: String? = null,
        /** The book's one-line summary ("CIN -6 · O/U 54.5"); null when the feed carries no odds. */
        val odds: String? = null,
        /** ESPN's team ids (A is the home side), and the league path the board came from: the key a game's photo is found by. */
        val idA: String? = null, val idB: String? = null,
        val leaguePath: String? = null,
        /** A real game/highlight image from the scoreboard, when the feed supplies one. */
        val image: String? = null,
    )

    /** [date] is yyyyMMdd; null means today. */
    override suspend fun scoreboard(sportPath: String, date: String?): List<Score> =
        scoreboardOrNull(sportPath, date) ?: emptyList()

    /** Null when the request or the document failed; an empty list only when ESPN really lists no games. */
    override suspend fun scoreboardOrNull(sportPath: String, date: String?): List<Score>? = try {
        val body = HttpSupport.get("$BASE_URL/$sportPath/scoreboard${query(sportPath, date)}")
        EspnScoreboardMapper.toScores(body, maxEvents = if (sportPath in COLLEGE_QUERY) MAX_COLLEGE_EVENTS else MAX_EVENTS)
            .map { it.copy(leaguePath = sportPath) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val BASE_URL = "https://site.api.espn.com/apis/site/v2/sports"
        private const val MAX_EVENTS = 30
        private const val MAX_COLLEGE_EVENTS = 150

        /** College boards show only a few featured games unless the division and a page size are asked for. */
        private val COLLEGE_QUERY = mapOf(
            "football/college-football" to "groups=80&limit=150",
            "basketball/mens-college-basketball" to "groups=50&limit=150",
            "basketball/womens-college-basketball" to "groups=50&limit=150",
        )

        internal fun query(sportPath: String, date: String?): String {
            val params = listOfNotNull(date?.let { "dates=$it" }, COLLEGE_QUERY[sportPath])
            return if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
        }
    }
}
