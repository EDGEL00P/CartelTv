package com.carteltv.tv.data.trakt
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide Trakt state + in-memory watched caches keyed by lowercase title. */
object TraktAuth {
    var client: TraktClient? = null
        private set

    var watchedMovies: Set<String> = emptySet(); private set
    var watchedEpisodes: Set<String> = emptySet(); private set   // "title:s:e"
    var userName: String? = null; private set

    fun bootstrap() {
        val cid = AppPreferences.traktClientId
        val tok = AppPreferences.traktToken
        client = if (cid.isNotBlank() && tok.isNotBlank()) {
            TraktClient(cid, tok, "")
        } else null
    }

    private val refreshLock = Mutex()
    private const val REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000L

    /** True once Trakt has rejected the refresh token, so the user has to pair again. An offline refresh never sets it. */
    var needsReconnect: Boolean = false; private set

    internal fun needsRefresh(expiresAtMs: Long, nowMs: Long): Boolean =
        expiresAtMs == 0L || nowMs >= expiresAtMs - REFRESH_MARGIN_MS

    private fun store(tokens: TraktClient.Tokens) {
        AppPreferences.traktToken = tokens.access
        AppPreferences.traktRefresh = tokens.refresh
        AppPreferences.traktExpiresAtMs = System.currentTimeMillis() + tokens.expiresIn * 1000
        client = TraktClient(AppPreferences.traktClientId, tokens.access, "")
    }

    fun completePairing(tokens: TraktClient.Tokens) {
        store(tokens)
        needsReconnect = false
    }

    fun unpair() {
        AppPreferences.traktToken = ""
        AppPreferences.traktRefresh = ""
        AppPreferences.traktExpiresAtMs = 0L
        needsReconnect = false
        userName = null
        client = null
    }

    /**
     * Trakt access tokens last 7 days and refresh tokens are single-use, so refreshes run one at a time
     * and the new pair is stored together. Without the client secret the old tokens are left alone.
     */
    suspend fun ensureFresh() {
        if (AppPreferences.traktToken.isBlank() || AppPreferences.traktRefresh.isBlank() || AppPreferences.traktClientSecret.isBlank()) return
        if (!needsRefresh(AppPreferences.traktExpiresAtMs, System.currentTimeMillis())) return
        refreshLock.withLock {
            // A caller that waited on the lock finds the tokens already renewed.
            if (!needsRefresh(AppPreferences.traktExpiresAtMs, System.currentTimeMillis())) return
            try {
                store(TraktClient(AppPreferences.traktClientId, clientSecret = AppPreferences.traktClientSecret).refresh(AppPreferences.traktRefresh))
                needsReconnect = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 400 or 401 means the refresh token is dead; offline or a 5xx is retried on the next call.
                if (e.message?.let { "HTTP 400" in it || "HTTP 401" in it } == true) needsReconnect = true
            }
        }
    }

    /** The accounts' watchlist, or empty when Trakt is not linked. */
    suspend fun watchlist(): List<TraktClient.WatchlistItem> {
        ensureFresh()
        return client?.watchlist().orEmpty()
    }

    suspend fun refreshAccount() {        ensureFresh()
        val c = client ?: return
        userName = suspendRunCatching { c.me().username }.getOrNull()
        watchedMovies = suspendRunCatching { c.watchedMovies() }.getOrDefault(emptyList())
            .map { it.title.lowercase() }.toSet()
        watchedEpisodes = suspendRunCatching { c.watchedShows() }.getOrDefault(emptyList())
            .flatMap { ws -> ws.seasons.flatMap { s -> s.episodes.map { e -> "${ws.title.lowercase()}:${s.number}:${e.number}" } } }
            .toSet()
    }

    fun isMovieWatched(title: String) = title.lowercase() in watchedMovies
    fun isEpisodeWatched(show: String, season: Int, episode: Int) =
        "$show:$season:$episode".lowercase() in watchedEpisodes
    suspend fun scrobbleMovie(title: String, year: Int?) {
        ensureFresh()
        client?.markMovieWatched(title, year)
        refreshAccount()
    }
    suspend fun scrobbleEpisode(showTitle: String, season: Int, episode: Int) {
        ensureFresh()
        client?.markEpisodeWatched(showTitle, season, episode)
        refreshAccount()
    }
}
