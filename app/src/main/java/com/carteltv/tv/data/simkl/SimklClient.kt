package com.carteltv.tv.data.simkl

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.watch.Scrobble
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Simkl tracker client (SecretVault-backed & API-24 safe).
 *
 * Supports OAuth 2.0 Device Flow (`/oauth2/device` + `/oauth2/token` + token refresh) with
 * legacy `/oauth/pin` fallback, 429 daily-quota UTC-midnight backoff, `/sync/activities`
 * change-stamp gating, Continue Watching playback sync, Plan-to-Watch list, and pause scrobbling.
 */
object SimklClient {
    data class PinCode(
        val userCode: String,
        val verifyUrl: String,
        val intervalSec: Int,
        val expiresInSec: Int,
        val deviceCode: String? = null,
        val qrUrl: String = verifyUrl,
    )

    data class PausedItem(
        val id: Long,
        val imdbId: String,
        val season: Int,
        val episode: Int,
        val percent: Int,
        val pausedAtMs: Long,
    )

    data class WatchlistItem(
        val imdbId: String,
        val type: String,
        val title: String,
        val posterUrl: String,
    )

    @Volatile var lastError: String = ""
        private set

    @Volatile private var activityStamp: String? = null

    @Volatile private var activityCheckedAt = 0L

    @Volatile private var playbackStamp: String? = null

    @Volatile private var playbackCache: List<PausedItem>? = null

    @Volatile private var planStamp: String? = null

    @Volatile private var planCache: List<WatchlistItem>? = null

    private val FRACTIONAL_SECONDS = Regex("\\.\\d+Z$")

    val isConfigured: Boolean get() = AppPreferences.simklClientId.isNotBlank()
    val isConnected: Boolean get() = AppPreferences.simklToken.isNotBlank()
    val userName: String get() = AppPreferences.simklUser
    val isRateLimited: Boolean get() = System.currentTimeMillis() < AppPreferences.simklBlockedUntil

    /** Computes the next UTC midnight + 10 min backoff timestamp for Simkl daily 429 limits (API-24 safe). */
    fun nextUtcResetMs(nowMs: Long = System.currentTimeMillis()): Long {
        val cal =
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 10)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        return cal.timeInMillis
    }

    /** Parses ISO-8601 UTC timestamp (`2026-11-20T12:08:56Z`) safely on API 24+. */
    fun parseIsoUtcMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        val normalized = raw.trim().replace(FRACTIONAL_SECONDS, "Z")
        val fmt =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        return runCatching { fmt.parse(normalized)?.time ?: 0L }.getOrDefault(0L)
    }

    fun signOut() {
        AppPreferences.simklToken = ""
        AppPreferences.simklRefresh = ""
        AppPreferences.simklUser = ""
        AppPreferences.simklExpiresAt = 0L
        AppPreferences.simklBlockedUntil = 0L
        invalidateActivity()
    }

    private fun invalidateActivity() {
        activityCheckedAt = 0L
        playbackStamp = null
        planStamp = null
    }

    private fun recordRateLimit(daily: Boolean) {
        val until = if (daily) nextUtcResetMs() else System.currentTimeMillis() + 5 * 60_000L
        AppPreferences.simklBlockedUntil = until
        lastError =
            if (daily) {
                "Simkl daily limit reached; retrying after UTC reset"
            } else {
                "Simkl asked to slow down; retrying in 5 min"
            }
    }

    private suspend fun postForm(
        path: String,
        fields: Map<String, String>,
    ): Pair<Int, String> {
        val body =
            fields.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
        return HttpSupport.request(
            "https://api.simkl.com$path",
            "POST",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
    }

    /** Simkl returns schemeless URIs (`simkl.com/pin?...`); a QR of that scans as plain text, not a link. */
    internal fun httpsUrl(raw: String): String {
        val v = raw.trim()
        return when {
            v.isBlank() || v.startsWith("https://") || v.startsWith("http://") -> v

            // bughunt:ignore kt-cleartext-http
            else -> "https://$v"
        }
    }

    private fun saveTokens(obj: JSONObject) {
        AppPreferences.simklToken = obj.getString("access_token")
        val refresh = obj.optString("refresh_token")
        if (refresh.isNotBlank()) AppPreferences.simklRefresh = refresh
        if (obj.has("expires_in")) {
            AppPreferences.simklExpiresAt = System.currentTimeMillis() + obj.optLong("expires_in") * 1000L
        }
    }

    private val refreshMutex = Mutex()

    private suspend fun refreshToken(): Boolean =
        runCatching {
            refreshMutex.withLock {
                val r = AppPreferences.simklRefresh
                val cid = AppPreferences.simklClientId
                if (r.isBlank() || cid.isBlank()) return@withLock false
                val (code, text) =
                    postForm(
                        "/oauth2/token",
                        mapOf("grant_type" to "refresh_token", "refresh_token" to r, "client_id" to cid),
                    )
                if (code !in 200..299) return@withLock false
                saveTokens(JSONObject(text))
                true
            }
        }.getOrDefault(false)

    private suspend fun refreshIfDue() {
        val exp = AppPreferences.simklExpiresAt
        if (exp > 0L && System.currentTimeMillis() > exp - 24 * 3600_000L) refreshToken()
    }

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        retried: Boolean = false,
    ): Pair<Int, String> {
        if (isRateLimited) return 429 to lastError
        if (method != "GET") invalidateActivity()
        refreshIfDue()
        val cid = AppPreferences.simklClientId
        val tok = AppPreferences.simklToken
        val sep = if ('?' in path) '&' else '?'
        val headers =
            mapOf(
                "Content-Type" to "application/json",
                "simkl-api-key" to cid,
                // Simkl requires a User-Agent on every request (https://api.simkl.org/conventions/headers).
                "User-Agent" to "CartelTV/1.0",
            ) + (if (tok.isNotBlank()) mapOf("Authorization" to "Bearer $tok") else emptyMap())
        val (code0, text0) =
            HttpSupport.request(
                "https://api.simkl.com$path${sep}client_id=$cid&app-name=carteltv&app-version=1.0",
                method,
                headers,
                body = body?.toString(),
                contentType = "application/json",
            )
        if (code0 == 429) {
            recordRateLimit(daily = text0.contains("user_limit", true) || text0.contains("daily", true))
            return code0 to text0
        }
        if (code0 == 401 && tok.isNotBlank()) {
            if (!retried && refreshToken()) return call(method, path, body, retried = true)
            signOut()
        }
        return code0 to text0
    }

    /** Starts OAuth 2.0 device pairing, falling back to legacy `/oauth/pin`. */
    suspend fun startPairing(): PinCode {
        val cid = AppPreferences.simklClientId
        check(cid.isNotBlank()) { "Configure Simkl Client ID first" }
        val (dc, dt) = postForm("/oauth2/device", mapOf("client_id" to cid, "scope" to "media:read media:write"))
        if (dc in 200..299) {
            val o = JSONObject(dt)
            val uri = httpsUrl(o.optString("verification_uri", "https://simkl.com/pin"))
            return PinCode(
                userCode = o.getString("user_code"),
                verifyUrl = uri,
                intervalSec = o.optInt("interval", 5),
                expiresInSec = o.optInt("expires_in", 900),
                deviceCode = o.getString("device_code"),
                qrUrl = httpsUrl(o.optString("verification_uri_complete")).ifBlank { uri },
            )
        }
        val (code, text) = call("GET", "/oauth/pin")
        check(code in 200..299) { "Simkl pairing HTTP $dc / $code" }
        val o = JSONObject(text)
        val u = httpsUrl(o.optString("verification_url", "https://simkl.com/pin/"))
        val userCode = o.getString("user_code")
        return PinCode(userCode, u, o.optInt("interval", 5), o.optInt("expires_in", 900), qrUrl = "${u.trimEnd('/')}/$userCode")
    }

    /** Polls Simkl until approved, cancelled, or expired. Suspends; cancellation stops the poll. */
    suspend fun pollPairing(pin: PinCode): Boolean {
        val deadline = System.currentTimeMillis() + pin.expiresInSec * 1000L
        var interval = pin.intervalSec.coerceAtLeast(2)
        val cid = AppPreferences.simklClientId
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(interval * 1000L)
            if (pin.deviceCode != null) {
                val (code, text) =
                    runCatching {
                        postForm(
                            "/oauth2/token",
                            mapOf(
                                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                                "client_id" to cid,
                                "device_code" to pin.deviceCode,
                            ),
                        )
                    }.getOrNull() ?: continue
                val o = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (code in 200..299 && o.optString("access_token").isNotBlank()) {
                    saveTokens(o)
                    fetchUserName()
                    return true
                }
                when (o.optString("error")) {
                    "slow_down" -> {
                        interval += 5
                    }

                    "expired_token" -> {
                        lastError = "Code expired"
                        return false
                    }

                    "invalid_client" -> {
                        lastError = "Invalid Simkl Client ID"
                        return false
                    }
                }
            } else {
                val (code, text) = runCatching { call("GET", "/oauth/pin/${pin.userCode}") }.getOrNull() ?: continue
                if (code !in 200..299) continue
                val o = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (o.optString("result") == "OK" && o.optString("access_token").isNotBlank()) {
                    AppPreferences.simklToken = o.getString("access_token")
                    fetchUserName()
                    return true
                }
            }
        }
        lastError = "Code expired"
        return false
    }

    private suspend fun fetchUserName() {
        runCatching {
            val (_, body) = call("GET", "/users/settings")
            JSONObject(body).optJSONObject("user")?.optString("name")
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { AppPreferences.simklUser = it }
    }
}
