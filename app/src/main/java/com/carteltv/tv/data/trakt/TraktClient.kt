package com.carteltv.tv.data.trakt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

class TraktHttpException(
    val code: Int,
) : IllegalStateException("Trakt HTTP $code")

/**
 * trakt.tvPlaceholderNever — OAuth 2.0 DEVICE flow (TV-first, no browser needed on device:
 * user visits trakt.tv/activate on their phone, enters the code we display).
 *
 * Needs only a client_id (public identifier) from https://trakt.tv/oauth/applications
 * — no secret required for the device grant. Stored in AppPreferences via Settings paste-in.
 */
class TraktClient(
    private val clientId: String,
    private var accessToken: String? = null,
    private val clientSecret: String = "",
) {
    @Serializable data class DeviceCode(
        @kotlinx.serialization.SerialName("device_code") val deviceCode: String,
        @kotlinx.serialization.SerialName("user_code") val userCode: String,
        @SerialName("verification_url") val verificationUrl: String = "https://trakt.tv/activate",
        @SerialName("expires_in") val expiresIn: Int = 600,
        @SerialName("interval") val intervalSec: Int = 5,
    )

    @Serializable data class Tokens(
        @SerialName("access_token") val access: String,
        @SerialName("refresh_token") val refresh: String,
        @SerialName("expires_in") val expiresIn: Long = 7776000,
    )

    private val json get() = com.carteltv.tv.data.network.HttpSupport.json

    private fun headers(auth: Boolean): Map<String, String> =
        buildMap {
            put("trakt-api-version", "2")
            put("trakt-api-key", clientId)
            if (auth) accessToken?.let { put("Authorization", "Bearer $it") }
        }

    private suspend fun post(
        path: String,
        body: String,
        auth: Boolean,
    ): String =
        withContext(Dispatchers.IO) {
            val (code, text) =
                com.carteltv.tv.data.network.HttpSupport.request(
                    url = "https://api.trakt.tv$path",
                    method = "POST",
                    headers = headers(auth),
                    body = body,
                    contentType = "application/json",
                    connectMs = 10_000,
                    readMs = 20_000,
                )
            if (code in 200..299) text else throw TraktHttpException(code)
        }

    private suspend fun get(path: String): String =
        com.carteltv.tv.data.network.HttpSupport
            .get("https://api.trakt.tv$path", headers(auth = true), connectMs = 10_000, readMs = 20_000)

    /**
     * Fetches every page of a Trakt list endpoint. Trakt paginates sync lists (page/limit, default
     * limit 100) and caps limit at 1000; without this a user with more than one page of watchlist
     * or watched items only ever saw page 1. Stops when a page returns fewer than [limit] items.
     */
    private suspend fun getPaged(
        path: String,
        limit: Int = 1000,
    ): List<String> {
        val sep = if ('?' in path) '&' else '?'
        val pages = ArrayList<String>()
        var page = 1
        while (true) {
            val raw = get("$path${sep}limit=$limit&page=$page")
            pages += raw
            val count = runCatching { json.parseToJsonElement(raw).jsonArray.size }.getOrDefault(0)
            if (count < limit) break
            page++
        }
        return pages
    }

    fun getBlocking(path: String): String =
        com.carteltv.tv.data.network.HttpSupport.getBlocking(
            "https://api.trakt.tv$path",
            headers(auth = true),
            connectMs = 10_000,
            readMs = 20_000,
        )

    suspend fun beginPairing(): DeviceCode {
        val raw = post("/oauth/device/code", """{"client_id":"$clientId"}""", auth = false)
        return json.decodeFromString<DeviceCode>(raw)
    }

    /**
     * One poll attempt; returns tokens once approved, null while pending (400) or on a transient failure.
     * Throws [TraktHttpException] for 404 (bad code), 409 (already used), 410 (expired), 418 (denied) and 429 (slow down).
     */
    suspend fun pollToken(device: DeviceCode): Tokens? =
        try {
            val sec = if (clientSecret.isNotBlank()) ""","client_secret":"$clientSecret"""" else ""
            val raw =
                post(
                    "/oauth/device/token",
                    """{"code":"${device.deviceCode}","client_id":"$clientId"$sec}""",
                    auth = false,
                )
            json.decodeFromString<Tokens>(raw)
        } catch (
            e: kotlin.coroutines.cancellation.CancellationException,
        ) {
            throw e
        } catch (e: TraktHttpException) {
            if (e.code in POLL_STOP_CODES) throw e else null
        } catch (_: Exception) {
            null
        }

    /** Swaps a refresh token for a new pair. Throws on any HTTP error so the caller can tell a dead token from a network failure. */
    suspend fun refresh(refreshToken: String): Tokens {
        val body =
            buildJsonObject {
                put("refresh_token", refreshToken)
                put("client_id", clientId)
                put("client_secret", clientSecret)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                put("grant_type", "refresh_token")
            }.toString()
        return json.decodeFromString<Tokens>(post("/oauth/token", body, auth = false))
    }

    @Serializable data class Me(
        val username: String? = null,
        val name: String? = null,
    )

    suspend fun me(): Me =
        runCatching {
            json.decodeFromString<Me>(get("/users/me"))
        }.getOrDefault(Me("(unknown)"))

    companion object {
        internal val POLL_STOP_CODES = setOf(404, 409, 410, 418, 429)
    }

    // ── watched surfaces: lowercase-title keyed caches ──
    @Serializable data class WatchedMovie(
        val title: String = "",
        val year: Int? = null,
        val plays: Int = 0,
    )

    @Serializable data class WatchedShow(
        val title: String = "",
        val seasons: List<SeasonW> = emptyList(),
    )

    @Serializable data class SeasonW(
        val number: Int = 0,
        val episodes: List<EpW> = emptyList(),
    )

    @Serializable data class EpW(
        val number: Int = 0,
        val plays: Int = 0,
    )

    @Serializable private data class WatchedMovieEnvelope(
        val plays: Int = 0,
        val movie: MediaStub? = null,
    )

    @Serializable private data class WatchedShowEnvelope(
        val show: MediaStub? = null,
        val seasons: List<SeasonW> = emptyList(),
    )

    @Serializable private data class MediaStub(
        val title: String = "",
        val year: Int? = null,
        val ids: MediaIds? = null,
    )

    @Serializable private data class MediaIds(
        val imdb: String? = null,
        val tmdb: Int? = null,
        val slug: String? = null,
    )

    @Serializable private data class WatchlistEnvelope(
        val movie: MediaStub? = null,
        val show: MediaStub? = null,
    )

    /** One watchlist entry with whatever id Trakt still holds. */
    data class WatchlistItem(
        val title: String,
        val year: Int?,
        val imdb: String?,
        val tmdb: Int?,
        val series: Boolean,
    )

    internal fun parseWatchlist(raw: String): List<WatchlistItem> =
        runCatching {
            json.decodeFromString<List<WatchlistEnvelope>>(raw).mapNotNull { env ->
                val stub = env.movie ?: env.show ?: return@mapNotNull null
                val title = stub.title.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WatchlistItem(title, stub.year, stub.ids?.imdb, stub.ids?.tmdb, series = env.show != null)
            }
        }.getOrDefault(emptyList())

    suspend fun watchlist(): List<WatchlistItem> =
        runCatching {
            getPaged("/sync/watchlist/movies").flatMap { parseWatchlist(it) } +
                getPaged("/sync/watchlist/shows").flatMap { parseWatchlist(it) }
        }.getOrDefault(emptyList())

    internal fun parseWatchedMovies(raw: String): List<WatchedMovie> =
        runCatching {
            json.decodeFromString<List<WatchedMovieEnvelope>>(raw).mapNotNull { env ->
                val t = env.movie?.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WatchedMovie(title = t, year = env.movie.year, plays = env.plays)
            }
        }.getOrDefault(emptyList())

    internal fun parseWatchedShows(raw: String): List<WatchedShow> =
        runCatching {
            json.decodeFromString<List<WatchedShowEnvelope>>(raw).mapNotNull { env ->
                val t = env.show?.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WatchedShow(title = t, seasons = env.seasons)
            }
        }.getOrDefault(emptyList())

    suspend fun watchedMovies(): List<WatchedMovie> =
        runCatching {
            getPaged("/sync/watched/movies").flatMap { parseWatchedMovies(it) }
        }.getOrDefault(emptyList())

    suspend fun watchedShows(): List<WatchedShow> =
        runCatching {
            getPaged("/sync/watched/shows").flatMap { parseWatchedShows(it) }
        }.getOrDefault(emptyList())

    /** Mark a movie as watched (fuzzy-matched by title/year on Trakt's side). */
    suspend fun markMovieWatched(
        title: String,
        year: Int?,
    ): Boolean =
        try {
            val yr = year?.let { "\"year\":$it," } ?: ""
            val body = """{"movies":[{$yr"title":"${title.replace("\"", "'")}","watched_at":"${java.time.Instant.now()}"}]}"""
            post("/sync/history", body, auth = true).isNotBlank()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    /** Mark an episode watched. */
    suspend fun markEpisodeWatched(
        showTitle: String,
        season: Int,
        episode: Int,
    ): Boolean =
        try {
            post(
                "/sync/history",
                """{"shows":[{"title":"${showTitle.replace(
                    "\"",
                    "'",
                )}","seasons":[{"number":$season,"episodes":[{"number":$episode,"watched_at":"${java.time.Instant.now()}"}]}]}]}""",
                auth = true,
            ).isNotBlank()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
}
