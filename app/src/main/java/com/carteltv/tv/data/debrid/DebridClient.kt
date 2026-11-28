package com.carteltv.tv.data.debrid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real-Debrid REST client (api.real-debrid.com) — token auth.
 * Token comes from real-debrid.com/apitoken (paste-once on TV).
 */
class DebridClient(
    private val token: String,
) {
    private val json get() = com.carteltv.tv.data.network.HttpSupport.lenientJson
    private val base = "https://api.real-debrid.com/rest/1.0"

    @Serializable data class RdUser(
        val username: String = "",
        val email: String? = null,
        val type: String = "",
        val premium: Long = 0,
        @SerialName("is_premium") val isPremiumFlag: Boolean = false,
        @SerialName("expiration") val expiration: String? = null,
        val points: Int = 0,
    ) {
        val isPremium: Boolean get() = isPremiumFlag || type.equals("premium", ignoreCase = true) || premium > 0
    }

    @Serializable data class Torrent(
        val id: String = "",
        val filename: String = "",
        val name: String? = null,
        val status: String = "", // downloading / downloaded / ready / …
        val hash: String? = null,
        val progress: Double = 0.0,
        val bytes: Long = 0,
        @SerialName("original_bytes") val originalBytes: Long = 0,
        val links: List<String> = emptyList(),
        val ended: String? = null,
        val host: String? = null,
    )

    @Serializable data class Unrestricted(
        val download: String = "",
        val filename: String = "",
        @SerialName("filesize") val fileSize: Long = 0,
        val host: String? = null,
    )

    // Real-Debrid allows 250 requests/min and counts refused calls, so a 429 gets one pause-and-retry, not a loop.
    private suspend fun req(
        path: String,
        method: String = "GET",
        body: String? = null,
        form: Boolean = false,
    ): Pair<Int, String> {
        suspend fun once() =
            com.carteltv.tv.data.network.HttpSupport.request(
                url = "$base$path",
                method = method,
                headers = mapOf("Authorization" to "Bearer $token"),
                body = body,
                contentType = if (form) "application/x-www-form-urlencoded" else "application/json",
            )
        val first = once()
        if (first.first != 429) return first
        kotlinx.coroutines.delay(3_000)
        return once()
    }

    suspend fun user(): RdUser =
        withContext(Dispatchers.IO) {
            val (code, body) = req("/user")
            if (code != 200) throw IllegalStateException("RD auth failed ($code)")
            json.decodeFromString<RdUser>(body)
        }

    suspend fun torrents(page: Int = 1): List<Torrent> =
        withContext(Dispatchers.IO) {
            val (code, body) = req("/torrents?page=$page&limit=50")
            if (code != 200) return@withContext emptyList()
            runCatching { json.decodeFromString<List<Torrent>>(body) }.getOrDefault(emptyList())
        }

    /** Adds a magnet and returns its torrent id. */
    suspend fun addMagnet(magnetUri: String): String? =
        withContext(Dispatchers.IO) {
            val (_, body) =
                req(
                    "/torrents/addMagnet",
                    method = "POST",
                    body = "magnet=${URLEncoder.encode(magnetUri, "UTF-8")}",
                    form = true,
                )
            runCatching {
                (json.parseToJsonElement(body) as? JsonObject)?.get("id")?.jsonPrimitive?.content
            }.getOrNull()
        }

    /** Select all files then wait for 'downloaded'/links. */
    suspend fun selectAllFiles(torrentId: String): Boolean =
        withContext(Dispatchers.IO) {
            val (code, _) = req("/torrents/selectFiles/$torrentId", method = "POST", body = "files=all", form = true)
            code == 201 || code == 204 || code == 200
        }

    suspend fun torrentInfo(torrentId: String): Torrent? =
        withContext(Dispatchers.IO) {
            val (code, body) = req("/torrents/info/$torrentId")
            if (code != 200) null else runCatching { json.decodeFromString<Torrent>(body) }.getOrNull()
        }

    /** Which of [hashes] RD's cloud can serve instantly, or null when the check itself failed (so "unknown", not "none").
     *  204 means nothing in that batch is cached, which is a valid empty answer. */
    suspend fun instantHashes(hashes: List<String>): Set<String>? =
        withContext(Dispatchers.IO) {
            if (hashes.isEmpty()) return@withContext emptySet()
            val found = mutableSetOf<String>()
            for (batch in hashes.chunked(20)) {
                val (code, body) = req("/torrents/instantavailability/${batch.joinToString("/")}")
                if (code == 204) continue
                if (code != 200) return@withContext null
                val parsed =
                    runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                        ?: return@withContext null
                // RD disabled this endpoint in 2026 (error_code 37): any error body means
                // "unknown", not a per-hash "none", so bail instead of looping every batch.
                if (parsed.containsKey("error_code") || parsed.containsKey("error")) return@withContext null
                parsed.keys.forEach { found += it.lowercase() }
            }
            found
        }

    /** Deletes from the cloudLocker. */
    suspend fun deleteTorrent(torrentId: String): Boolean =
        withContext(Dispatchers.IO) {
            val (code, _) = req("/torrents/delete/$torrentId", method = "DELETE")
            code == 204 || code == 200
        }

    /** Turns an rd-hosted link into a direct https download/stream URL. */
    suspend fun unrestrict(link: String): Unrestricted? =
        withContext(Dispatchers.IO) {
            val (_, body) = req("/unrestrict/link", method = "POST", body = "link=${URLEncoder.encode(link, "UTF-8")}", form = true)
            runCatching { json.decodeFromString<Unrestricted>(body) }.getOrNull()
        }

    /** Highest-size file link of a finished torrent. Current pick: first selected
     *  link (order = selection order); quality→size ranking arrives with tier-ladder work. */
    suspend fun bestStreamLink(t: Torrent): String? {
        if (t.status != "downloaded" || t.links.isEmpty()) return null
        return t.links.firstOrNull()
    }

    companion object {
        fun normalizeToken(raw: String) =
            com.carteltv.tv.data.network.HttpSupport
                .normalizeBearerToken(raw)
    }
}
