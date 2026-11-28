package com.carteltv.tv.data.debrid

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Torbox device-code login (GET /user/auth/device/start, POST /user/auth/device/token): the TV shows a code,
 * the user approves it on a phone, and the TV collects the user's API key. The key is checked against
 * /user/me before it is saved, because Torbox does not document the shape of the success response.
 */
object TorboxAuth {
    private const val BASE = "https://api.torbox.app/v1/api/user/auth/device"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresIn: Int,
    )

    sealed interface Poll {
        data object Pending : Poll
        data class Token(val value: String) : Poll
        data class Failed(val message: String) : Poll
    }

    suspend fun begin(): DeviceCode {
        val (code, body) = HttpSupport.request("$BASE/start?app=CartelTV")
        check(code == 200) { "Torbox could not start linking ($code)" }
        return parseDeviceCode(body)
    }

    /** True once the user approved and a working key is saved; false while still waiting. */
    suspend fun poll(device: DeviceCode): Boolean {
        val (status, body) = HttpSupport.request(
            "$BASE/token", method = "POST",
            body = """{"device_code":"${device.deviceCode}"}""", contentType = "application/json",
        )
        when (val r = parsePoll(status, body)) {
            Poll.Pending -> return false
            is Poll.Failed -> throw IllegalStateException(r.message)
            is Poll.Token -> {
                val key = TorboxClient.normalizeToken(r.value)
                TorboxClient(key).user()
                AppPreferences.torboxToken = key
                return true
            }
        }
    }

    internal fun parseDeviceCode(body: String): DeviceCode {
        val data = envelope(body)?.get("data") as? JsonObject
            ?: throw IllegalStateException("Torbox sent an unexpected reply")
        fun str(k: String) = (data[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val device = str("device_code")
        val user = str("code")
        require(device.isNotBlank() && user.isNotBlank()) { "Torbox sent an incomplete code" }
        return DeviceCode(
            deviceCode = device,
            userCode = user,
            verificationUrl = str("verification_url_complete").ifBlank { str("verification_url") }.ifBlank { "https://torbox.app/oauth/device" },
            intervalSec = ((data["interval"] as? JsonPrimitive)?.intOrNull ?: 5).coerceAtLeast(1),
            expiresIn = (data["expires_in"] as? JsonPrimitive)?.intOrNull ?: 600,
        )
    }

    internal fun parsePoll(status: Int, body: String): Poll {
        val root = envelope(body)
        if (status == 200 && root?.get("success")?.jsonPrimitive?.contentOrNull == "true") {
            val data = root["data"]
            val token = (data as? JsonPrimitive)?.contentOrNull
                ?: (data as? JsonObject)?.let { o -> TOKEN_KEYS.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull } }
            return if (token.isNullOrBlank()) Poll.Failed("Torbox sent no key") else Poll.Token(token)
        }
        return when (root?.get("error")?.jsonPrimitive?.contentOrNull) {
            "DEVICE_CODE_NOT_USED" -> Poll.Pending
            "ITEM_NOT_FOUND" -> Poll.Failed("The code expired. Try again.")
            "DEVICE_CODE_USED" -> Poll.Failed("That code was already used. Try again.")
            // Rate limits, 5xx and network failures say nothing about the code; keep polling.
            else -> if (status == 429 || status >= 500 || status == -1) Poll.Pending
            else Poll.Failed("Torbox refused the code ($status)")
        }
    }

    private val TOKEN_KEYS = listOf("token", "access_token", "api_key", "apikey")

    private fun envelope(body: String): JsonObject? =
        runCatching { HttpSupport.lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
}
