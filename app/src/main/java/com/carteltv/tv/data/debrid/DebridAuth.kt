package com.carteltv.tv.data.debrid

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Real-Debrid "open source app" device flow: the TV shows a code, the user approves on a phone, and the
 * TV polls for the grant. Tokens from this flow last an hour, so [ensureFresh] renews them from the
 * refresh token before any Real-Debrid call. A key the user pasted by hand never expires and is left alone.
 */
object DebridAuth {
    /** Public client id Real-Debrid documents for open-source apps; it is not a secret. */
    private const val OPEN_CLIENT_ID = "X245A4XAIBGVM"
    private const val OAUTH = "https://api.real-debrid.com/oauth/v2"
    private const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0" // bughunt:ignore kt-cleartext-http
    private const val RENEW_MARGIN_MS = 120_000L

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresIn: Int,
    )

    private val refreshLock = Mutex()

    suspend fun begin(): DeviceCode {
        val (code, body) = HttpSupport.request("$OAUTH/device/code?client_id=$OPEN_CLIENT_ID&new_credentials=yes")
        check(code == 200) { "Real-Debrid could not start linking ($code)" }
        return parseDeviceCode(body)
    }

    /** True once the user has approved and the tokens are saved; false while still waiting. */
    suspend fun poll(device: DeviceCode): Boolean {
        val (code, body) = HttpSupport.request(
            "$OAUTH/device/credentials?client_id=$OPEN_CLIENT_ID&code=${enc(device.deviceCode)}",
        )
        if (code != 200) return false
        val creds = JSONObject(body)
        val clientId = creds.optString("client_id")
        val secret = creds.optString("client_secret")
        if (clientId.isBlank() || secret.isBlank()) return false
        val grant = tokenRequest(clientId, secret, device.deviceCode, DEVICE_GRANT)
        save(grant, clientId, secret)
        return true
    }

    /** Renews the saved access token when it is near expiry. Safe to call before every Real-Debrid use. */
    suspend fun ensureFresh() {
        val rec = AppPreferences.debridOauth ?: return
        if (AppPreferences.debridToken != rec.accessToken) return
        if (System.currentTimeMillis() < rec.expiresAtMs - RENEW_MARGIN_MS) return
        refreshLock.withLock {
            val now = AppPreferences.debridOauth ?: return
            if (AppPreferences.debridToken != now.accessToken) return
            if (System.currentTimeMillis() < now.expiresAtMs - RENEW_MARGIN_MS) return
            val grant = tokenRequest(now.clientId, now.clientSecret, now.refreshToken, DEVICE_GRANT)
            save(grant, now.clientId, now.clientSecret)
        }
    }

    /** For call sites that go on with the old token if renewing fails; the Real-Debrid call then reports the real error. */
    suspend fun ensureFreshOrKeep() {
        try {
            ensureFresh()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { // bughunt:ignore kt-empty-catch
        }
    }

    internal fun parseDeviceCode(body: String): DeviceCode {
        val j = Json.parseToJsonElement(body).jsonObject
        fun str(k: String) = j[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun num(k: String, d: Int) = j[k]?.jsonPrimitive?.intOrNull ?: d
        val device = str("device_code")
        val user = str("user_code")
        require(device.isNotBlank() && user.isNotBlank()) { "Real-Debrid sent an incomplete code" }
        return DeviceCode(
            deviceCode = device,
            userCode = user,
            verificationUrl = str("verification_url").ifBlank { "https://real-debrid.com/device" },
            intervalSec = num("interval", 5).coerceAtLeast(1),
            expiresIn = num("expires_in", 600),
        )
    }

    private class Grant(val access: String, val refresh: String, val expiresInSec: Long)

    private suspend fun tokenRequest(clientId: String, secret: String, code: String, grantType: String): Grant {
        val form = "client_id=${enc(clientId)}&client_secret=${enc(secret)}&code=${enc(code)}&grant_type=${enc(grantType)}"
        val (status, body) = HttpSupport.request(
            "$OAUTH/token", method = "POST", body = form, contentType = "application/x-www-form-urlencoded",
        )
        check(status == 200) { "Real-Debrid refused the sign-in ($status)" }
        val j = JSONObject(body)
        val access = j.optString("access_token")
        check(access.isNotBlank()) { "Real-Debrid sent no token" }
        return Grant(access, j.optString("refresh_token"), j.optLong("expires_in", 3600))
    }

    private fun save(grant: Grant, clientId: String, secret: String) {
        AppPreferences.debridOauth = DebridOauth(
            clientId, secret, grant.refresh, grant.access,
            System.currentTimeMillis() + grant.expiresInSec * 1000L,
        )
        AppPreferences.debridToken = grant.access
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

data class DebridOauth(
    val clientId: String,
    val clientSecret: String,
    val refreshToken: String,
    val accessToken: String,
    val expiresAtMs: Long,
)
