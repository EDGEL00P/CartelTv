package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * YouTubeSession — OAuth2 token custody for the signed layer. Tracks expiry so
 * expired Google access tokens (~3600s lifetime) are refreshed cleanly instead
 * of pinning stale 401 Bearer headers for the lifetime of the process.
 */
object YouTubeSession {

    private const val REFRESH_MARGIN_MS = 5L * 60L * 1000L
    private const val FAILURE_BACKOFF_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    @Volatile private var accessTok: String? = null
    @Volatile private var expiresAtMs: Long = 0L
    @Volatile private var retryAfterMs: Long = 0L
    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun isFresh(): Boolean =
        !accessTok.isNullOrBlank() && System.currentTimeMillis() + REFRESH_MARGIN_MS < expiresAtMs

    /** Current access token; kicks off a background refresh when missing or near expiry. */
    fun access(): String? {
        if (!isFresh() && AppPreferences.youTubeRefreshToken.isNotBlank() && refreshInFlight.compareAndSet(false, true)) {
            scope.launch {
                try { accessOrRefresh() } finally { refreshInFlight.set(false) }
            }
        }
        return accessTok.takeIf { isFresh() }
    }

    /** Suspending token getter: refreshes immediately when missing or expired. */
    suspend fun accessOrRefresh(): String? {
        if (isFresh()) return accessTok
        val refresh = AppPreferences.youTubeRefreshToken
        if (refresh.isBlank()) return null
        return refreshMutex.withLock {
            if (isFresh()) return@withLock accessTok
            if (System.currentTimeMillis() < retryAfterMs) return@withLock null
            val granted = try {
                YouTubeApiClient.accessTokenWithExpiry(refresh)
            } catch (e: YouTubeApiClient.OAuthRejectedException) {
                // The stored grant is permanently dead (revoked token or client ID rotated).
                // Clear it so the UI drops to signed-out and the viewer can sign in again,
                // rather than presenting a session whose account feeds never refresh.
                android.util.Log.w("CartelTube", "Refresh token permanently rejected — signing out")
                signOut()
                return@withLock null
            }
            if (granted != null) {
                accessTok = granted.first
                expiresAtMs = System.currentTimeMillis() + granted.second * 1000L
                retryAfterMs = 0L
            } else {
                retryAfterMs = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            }
            accessTok.takeIf { isFresh() }
        }
    }

    /** Clears the in-memory access token after an HTTP 401 so the next call re-mints it. */
    fun invalidateAccessToken() {
        accessTok = null
        expiresAtMs = 0L
    }

    /** Called by the login UI when pollLogin returns GRANTED:<refresh>. */
    fun granted(refreshToken: String) {
        AppPreferences.youTubeRefreshToken = refreshToken
        retryAfterMs = 0L
        invalidateAccessToken()
        scope.launch { accessOrRefresh() }
    }

    fun signedIn(): Boolean = AppPreferences.youTubeRefreshToken.isNotBlank()

    fun signOut() {
        AppPreferences.youTubeRefreshToken = ""
        invalidateAccessToken()
    }
}
