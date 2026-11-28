package com.carteltv.tv.data.stremio

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.network.isHttpUrl

import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.simkl.SimklClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stremio cloud account integration (SecretVault-backed).
 *
 * Supports phone-code pairing (`link.stremio.com/api/v2/create` & `read`), email/password
 * sign-in, one-tap `addonCollectionGet` import into `AppPreferences.addons()`, and Continue Watching
 * (`datastoreGet` `libraryItem`) pull + dismiss.
 */
object StremioAccount {

    data class LinkSession(val code: String, val verifyUrl: String)

    data class CloudProgress(
        val id: String,
        val imdbId: String,
        val season: Int,
        val episode: Int,
        val percent: Int,
        val watchedAtMs: Long,
        val rawJson: String,
    )

    @Volatile var lastError: String = ""
        private set

    val isConnected: Boolean get() = AppPreferences.stremioAuthKey.isNotBlank()
    val email: String get() = AppPreferences.stremioEmail

    fun signOut() {
        AppPreferences.stremioAuthKey = ""
        AppPreferences.stremioEmail = ""
    }

    /**
     * Pure filter for Stremio `addonCollectionGet` JSON responses (kotlinx.serialization — JVM & Android safe).
     * Stremio's protected stock addons are skipped: every account carries Cinemeta, so syncing it would bring back
     * a Cinemeta the user removed here in favour of their own metadata addon.
     */
    fun parseAddonUrls(rawJson: String): List<String> {
        val root = com.carteltv.tv.data.network.HttpSupport.lenientJson.parseToJsonElement(rawJson) as? kotlinx.serialization.json.JsonObject
            ?: return emptyList()
        val result = root["result"] as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val arr = result["addons"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr
            .mapNotNull { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                (obj["transportUrl"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isHttpUrl() }
            }
            .filterNot { u ->
                u.contains("v3-cinemeta") || u.contains("opensubtitles") || u.contains("local.strem.io")
            }
            .distinct()
    }

    private fun http(url: String, body: JSONObject? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = HttpSupport.CONNECT_MS
        conn.readTimeout = HttpSupport.READ_MS
        conn.setRequestProperty("Content-Type", "application/json")
        return try {
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to runCatching { stream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun api(method: String, body: JSONObject): JSONObject {
        val (code, text) = http("https://api.strem.io/api/$method", body.put("authKey", AppPreferences.stremioAuthKey))
        check(code in 200..299) { "Stremio HTTP $code" }
        val o = JSONObject(text)
        o.optJSONObject("error")?.let { e ->
            if (e.optInt("code") == 1) signOut()
            error(e.optString("message", "Stremio error"))
        }
        return o
    }

    fun startLink(): LinkSession {
        val (code, text) = http("https://link.stremio.com/api/v2/create?type=Create")
        check(code in 200..299) { "Stremio link HTTP $code" }
        val r = JSONObject(text).getJSONObject("result")
        val linkCode = r.getString("code")
        return LinkSession(linkCode, r.optString("link").ifBlank { "https://link.stremio.com/?code=$linkCode" })
    }

    /** Cooperative: cancelling the calling coroutine stops the poll at the next tick. */
    suspend fun pollLink(session: LinkSession): Boolean {
        val deadline = System.currentTimeMillis() + 10 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(3_000L)
            val (code, text) = try {
                http("https://link.stremio.com/api/v2/read?type=Read&code=${session.code}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            if (code !in 200..299) continue
            val auth = runCatching { JSONObject(text).optJSONObject("result")?.optString("authKey") }
                .getOrNull().orEmpty()
            if (auth.isNotBlank()) {
                AppPreferences.stremioAuthKey = auth
                runCatching {
                    api("getUser", JSONObject().put("type", "GetUser"))
                        .optJSONObject("result")?.optString("email")
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { AppPreferences.stremioEmail = it }
                return true
            }
        }
        lastError = "Link code expired"
        return false
    }

    /** Pulls installed Stremio cloud addons and merges them into [AppPreferences.addons]. Returns count added. */
    fun syncAddonsToPreferences(): Result<Int> = runCatching {
        val resp = api("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("update", true))
        val urls = parseAddonUrls(resp.toString())
        var added = 0
        for (url in urls) {
            if (AppPreferences.addAddon(url)) added++
        }
        added
    }.onFailure { lastError = it.message.orEmpty() }

}
