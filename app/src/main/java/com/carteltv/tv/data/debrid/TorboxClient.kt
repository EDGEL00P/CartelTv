package com.carteltv.tv.data.debrid

import com.carteltv.tv.data.network.isHttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Torbox.app REST client (api.torbox.app/v1/api) — bearer-token auth.
 * Token from torbox.app/settings → API (paste-once on TV).
 */
class TorboxClient(
    private val token: String,
) {
    private val json get() = com.carteltv.tv.data.network.HttpSupport.lenientJson
    private val base = "https://api.torbox.app/v1/api"

    @Serializable data class TbUser(
        val email: String? = null,
        @SerialName("is_subscribed") val isSubscribed: Boolean = false,
        val plan: Int = 0,
        @SerialName("is_premium") val isPremiumFlag: Boolean = false,
        @SerialName("premium_expires_at") val premiumExpires: String? = null,
    ) {
        val isPremium: Boolean get() = isSubscribed || isPremiumFlag || plan > 0
    }

    @Serializable data class TbFile(
        val id: Int = 0,
        val name: String = "",
        val size: Long = 0,
        @SerialName("short_name") val shortName: String? = null,
    )

    @Serializable data class TbTorrent(
        val id: Int = 0,
        val name: String = "",
        val size: Long = 0,
        val progress: Double = 0.0,
        @SerialName("download_state") val downloadState: String = "",
        @SerialName("download_finished") val downloadFinished: Boolean = false,
        @SerialName("download_present") val downloadPresent: Boolean = false,
        val files: List<TbFile> = emptyList(),
    )

    private suspend fun req(
        path: String,
        method: String = "GET",
        body: String? = null,
        form: Boolean = false,
        contentType: String? = null,
    ): Pair<Int, String> =
        com.carteltv.tv.data.network.HttpSupport.request(
            url = "$base$path",
            method = method,
            headers = mapOf("Authorization" to "Bearer $token"),
            body = body,
            contentType = contentType ?: if (form) "application/x-www-form-urlencoded" else "application/json",
        )

    private fun envelope(body: String): JsonObject? =
        runCatching {
            json.parseToJsonElement(body) as? JsonObject
        }.getOrNull()

    suspend fun user(): TbUser =
        withContext(Dispatchers.IO) {
            val (code, body) = req("/user/me")
            if (code != 200) throw IllegalStateException("Torbox auth failed ($code)")
            val data = envelope(body)?.get("data")
            if (data != null) {
                json.decodeFromJsonElement<TbUser>(data)
            } else {
                throw IllegalStateException("Unexpected Torbox envelope")
            }
        }

    suspend fun myList(): List<TbTorrent> =
        withContext(Dispatchers.IO) {
            val (code, body) = req("/torrents/mylist?bypass_cache=false")
            if (code != 200) return@withContext emptyList()
            val arr = envelope(body)?.get("data") as? JsonArray ?: return@withContext emptyList()
            arr.mapNotNull { el -> runCatching { json.decodeFromJsonElement<TbTorrent>(el) }.getOrNull() }
        }

    /** One torrent, read fresh: the list endpoint is cached for minutes and would not show a torrent added a moment ago. */
    suspend fun torrent(id: Int): TbTorrent? =
        withContext(Dispatchers.IO) {
            val (code, body) = req("/torrents/mylist?id=$id&bypass_cache=true")
            if (code != 200) return@withContext null
            val data = envelope(body)?.get("data")
            val el = (data as? JsonArray)?.firstOrNull() ?: data
            el?.let { runCatching { json.decodeFromJsonElement<TbTorrent>(it) }.getOrNull() }
        }

    /** With [onlyIfCached], TorBox adds nothing unless the torrent is already cached, so this never starts a download. */
    suspend fun addMagnet(
        magnet: String,
        onlyIfCached: Boolean = false,
    ): Int? =
        withContext(Dispatchers.IO) {
            // /torrents/createtorrent requires multipart/form-data (api-docs.torbox.app); the shared
            // transport takes a string body, so build the multipart payload here with a fixed boundary.
            val boundary = "CartelTVBoundary7MA4YWxkTrZu0gW"
            val parts =
                buildList {
                    add("--$boundary\r\nContent-Disposition: form-data; name=\"magnet\"\r\n\r\n$magnet\r\n")
                    add("--$boundary\r\nContent-Disposition: form-data; name=\"seed\"\r\n\r\n3\r\n")
                    add("--$boundary\r\nContent-Disposition: form-data; name=\"allow_zip\"\r\n\r\nfalse\r\n")
                    if (onlyIfCached) {
                        add("--$boundary\r\nContent-Disposition: form-data; name=\"add_only_if_cached\"\r\n\r\ntrue\r\n")
                    }
                }
            val body = parts.joinToString("") + "--$boundary--\r\n"
            val (code, resp) =
                req(
                    "/torrents/createtorrent",
                    "POST",
                    body,
                    contentType = "multipart/form-data; boundary=$boundary",
                )
            if (code != 200) {
                null
            } else {
                envelope(resp)?.let { root ->
                    (root["torrent_id"] as? JsonPrimitive)?.content?.toIntOrNull()
                        ?: ((root["data"] as? JsonObject)?.get("torrent_id") as? JsonPrimitive)?.content?.toIntOrNull()
                }
            }
        }

    suspend fun requestLink(
        torrentId: Int,
        fileId: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            val tok = URLEncoder.encode(token, "UTF-8")
            val (code, body) =
                req(
                    "/torrents/requestdl?token=$tok&torrent_id=$torrentId&file_id=$fileId&zip_link=false",
                    "GET",
                )
            if (code != 200) {
                null
            } else {
                (envelope(body)?.get("data") as? JsonPrimitive)?.content?.takeIf { it.isHttpUrl() }
            }
        }

    /** Which of [hashes] TorBox already holds, or null when the check itself failed (so "unknown", not "none"). */
    suspend fun cachedHashes(hashes: List<String>): Set<String>? =
        withContext(Dispatchers.IO) {
            if (hashes.isEmpty()) return@withContext emptySet()
            val (code, body) = req("/torrents/checkcached?hash=${hashes.joinToString(",")}&format=list&list_files=false")
            if (code != 200) null else parseCachedHashes(body)
        }

    suspend fun deleteTorrent(torrentId: Int): Boolean =
        withContext(Dispatchers.IO) {
            val (code, _) = req("/torrents/controltorrent", "POST", """{"torrent_id":$torrentId,"operation":"delete"}""")
            code == 200
        }

    /** Convenience: playable link for the largest file of a finished torrent. */
    suspend fun bestLink(t: TbTorrent): String? {
        if (!t.downloadFinished && !t.downloadPresent) return null
        val file = t.files.maxByOrNull { it.size } ?: return null
        return requestLink(t.id, file.id)
    }

    companion object {
        fun normalizeToken(raw: String) =
            com.carteltv.tv.data.network.HttpSupport
                .normalizeBearerToken(raw)

        /** checkcached answers with a list of hashes or of objects carrying one, a hash-keyed object, or null when nothing is cached. */
        internal fun parseCachedHashes(body: String): Set<String>? {
            val root =
                runCatching {
                    com.carteltv.tv.data.network.HttpSupport.lenientJson
                        .parseToJsonElement(body) as? JsonObject
                }.getOrNull()
                    ?: return null
            if (root["success"]?.let { (it as? JsonPrimitive)?.content } == "false") return null
            return when (val data = root["data"]) {
                is JsonArray -> {
                    data
                        .mapNotNull { el ->
                            (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?: ((el as? JsonObject)?.get("hash") as? JsonPrimitive)?.content
                        }.map { it.lowercase() }
                        .toSet()
                }

                is JsonObject -> {
                    data.keys.map { it.lowercase() }.toSet()
                }

                else -> {
                    emptySet()
                }
            }
        }
    }
}
