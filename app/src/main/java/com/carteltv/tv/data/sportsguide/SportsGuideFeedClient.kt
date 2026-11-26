package com.carteltv.tv.data.sportsguide

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Consumer for the static CartelTV sports-guide JSON published by GitHub Actions
 * (`tools/export_sports_guide_json.py` -> `sports-guide.json`).
 *
 * This is a schedule/rights feed, not a live scoreboard: generation happens in CI, the app
 * fetches one small JSON file over HTTP and caches it in memory.
 */
object SportsGuideFeedClient {

    const val DEFAULT_URL = "https://edgel00p.github.io/CartelTv/sports-guide.json"
    private const val TTL_MS = 5 * 60_000L

    @Volatile private var cache: CacheEntry? = null

    private data class CacheEntry(val url: String, val fetchedAtMs: Long, val feed: Feed)

    @Serializable
    data class Feed(
        val schema: String = "",
        @SerialName("generated_at") val generatedAt: String = "",
        val days: Int = 0,
        @SerialName("league_count") val leagueCount: Int = 0,
        @SerialName("game_count") val gameCount: Int = 0,
        val leagues: List<League> = emptyList(),
        val games: List<Game> = emptyList(),
        val source: Source = Source(),
    )

    @Serializable
    data class Source(
        val provider: String = "",
        @SerialName("scoreboard_api") val scoreboardApi: String = "",
        @SerialName("rights_file") val rightsFile: String = "",
    )

    @Serializable
    data class League(
        val path: String = "",
        val slug: String = "",
        val name: String = "",
        val regions: List<String> = emptyList(),
        @SerialName("game_hours") val gameHours: Double = 0.0,
        val events: Int = 0,
        @SerialName("rights_curated") val rightsCurated: List<String> = emptyList(),
    )

    @Serializable
    data class Game(
        val id: String = "",
        @SerialName("league_path") val leaguePath: String = "",
        @SerialName("league_slug") val leagueSlug: String = "",
        @SerialName("league_name") val leagueName: String = "",
        @SerialName("channel_id") val channelId: String = "",
        val title: String = "",
        @SerialName("short_title") val shortTitle: String? = null,
        @SerialName("start_utc") val startUtc: String = "",
        @SerialName("end_utc") val endUtc: String = "",
        val status: String? = null,
        val venue: String? = null,
        @SerialName("broadcasters_espn") val broadcastersEspn: List<String> = emptyList(),
        @SerialName("rights_curated") val rightsCurated: List<String> = emptyList(),
        val regions: List<String> = emptyList(),
        val source: String = "",
    )

    /** Fetch and parse the feed. Throws on transport/parse errors. */
    suspend fun fetch(url: String = DEFAULT_URL): Feed = withContext(Dispatchers.IO) {
        val body = HttpSupport.get(url, connectMs = 10_000, readMs = 20_000)
        HttpSupport.json.decodeFromString(Feed.serializer(), body)
    }

    /**
     * Cached feed. Returns stale cache on fetch failure when available, so a transient network
     * error does not blank guide data.
     */
    suspend fun cached(url: String = DEFAULT_URL, force: Boolean = false, maxAgeMs: Long = TTL_MS): Feed? {
        val now = System.currentTimeMillis()
        val cached = cache
        if (!force && cached != null && cached.url == url && now - cached.fetchedAtMs < maxAgeMs) return cached.feed
        return suspendRunCatching { fetch(url) }
            .onSuccess { cache = CacheEntry(url, now, it) }
            .getOrElse { cached?.feed?.takeIf { cached.url == url } }
    }

    fun clearCache() {
        cache = null
    }

    fun gamesForLeague(feed: Feed, leagueSlug: String): List<Game> =
        feed.games.filter { it.leagueSlug.equals(leagueSlug, ignoreCase = true) }

    fun gamesForChannel(feed: Feed, channelId: String): List<Game> =
        feed.games.filter { it.channelId.equals(channelId, ignoreCase = true) }

    /** Broadcasters from ESPN plus curated rights, de-duped preserving order. */
    fun allBroadcasters(game: Game): List<String> {
        val out = LinkedHashSet<String>()
        game.broadcastersEspn.forEach { it.trim().takeIf(String::isNotEmpty)?.let(out::add) }
        game.rightsCurated.forEach { it.trim().takeIf(String::isNotEmpty)?.let(out::add) }
        return out.toList()
    }
}
