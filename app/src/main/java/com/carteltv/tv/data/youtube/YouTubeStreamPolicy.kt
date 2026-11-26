package com.carteltv.tv.data.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure JVM-testable YouTube streaming policy & resilience primitives.
 * Synthesized from Flow-Tube/Flow (StreamDenialClassifier, ClientGateTracker,
 * GoogleVideoRequestPolicy, StreamHostFallback, SignatureCipher, PrerollWait)
 * and TeamNewPipe/NewPipe (YoutubeHttpDataSource, ClientsConstants).
 */
enum class StreamDenialKind {
    URL_EXPIRED,
    ATTESTATION_GATED,
    TOKEN_REJECTED,
    UNKNOWN,
}

object YouTubeStreamDenial {
    private const val EXPIRY_GRACE_SECONDS = 30L

    fun classify(
        url: String?,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): StreamDenialKind {
        val expire = queryParam(url, "expire")?.toLongOrNull() ?: return StreamDenialKind.UNKNOWN
        if (expire - nowSeconds <= EXPIRY_GRACE_SECONDS) return StreamDenialKind.URL_EXPIRED
        return if (queryParam(url, "pot").isNullOrEmpty()) {
            StreamDenialKind.ATTESTATION_GATED
        } else {
            StreamDenialKind.TOKEN_REJECTED
        }
    }

    fun clientOf(url: String?): String? =
        queryParam(url, "c")?.takeIf { it.isNotBlank() }?.uppercase()

    fun itagOf(url: String?): String? =
        queryParam(url, "itag")?.takeIf { it.isNotBlank() }

    fun hasPoToken(url: String?): Boolean = !queryParam(url, "pot").isNullOrEmpty()

    fun queryParam(url: String?, name: String): String? {
        if (url.isNullOrEmpty()) return null
        val queryStart = url.indexOf('?')
        if (queryStart < 0 || queryStart == url.length - 1) return null
        val query = url.substring(queryStart + 1).substringBefore('#')
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val sep = pair.indexOf('=')
            val key = if (sep < 0) pair else pair.substring(0, sep)
            if (key != name) continue
            val raw = if (sep < 0) "" else pair.substring(sep + 1)
            return decode(raw)
        }
        return null
    }

    fun appendParamIfMissing(url: String, key: String, value: String): String {
        if (queryParam(url, key) != null) return url
        val sep = if ('?' in url) '&' else '?'
        return "$url$sep$key=${URLEncoder.encode(value, "UTF-8")}"
    }

    private fun decode(raw: String): String =
        try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: IllegalArgumentException) {
            raw
        }
}

/**
 * Tracks InnerTube clients that GVS has refused so stream resolution stops
 * walking straight back into the same 60-second wall on every video.
 */
class YouTubeClientGate(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val onGated: (clientName: String) -> Unit = {},
) {
    private val gatedUntilMs = ConcurrentHashMap<String, Long>()
    private val refusalStrikes = ConcurrentHashMap<String, Int>()

    fun reportGated(clientName: String?) {
        val key = clientName?.takeIf { it.isNotBlank() }?.uppercase() ?: return
        gatedUntilMs[key] = clockMs() + ttlMs
        onGated(key)
    }

    fun reportRefused(clientName: String?): Boolean {
        val key = clientName?.takeIf { it.isNotBlank() }?.uppercase() ?: return false
        val strikes = refusalStrikes.merge(key, 1, Int::plus) ?: 1
        if (strikes < REFUSALS_BEFORE_DEMOTION) return false
        refusalStrikes.remove(key)
        gatedUntilMs[key] = clockMs() + ttlMs
        return true
    }

    fun reportDenied(url: String?): StreamDenialKind {
        val kind = YouTubeStreamDenial.classify(url, nowSeconds = clockMs() / 1000)
        val client = YouTubeStreamDenial.clientOf(url)
        when (kind) {
            StreamDenialKind.ATTESTATION_GATED -> reportGated(client)
            StreamDenialKind.TOKEN_REJECTED -> reportRefused(client)
            StreamDenialKind.URL_EXPIRED, StreamDenialKind.UNKNOWN -> Unit
        }
        return kind
    }

    fun isGated(clientName: String?): Boolean {
        val key = clientName?.takeIf { it.isNotBlank() }?.uppercase() ?: return false
        val until = gatedUntilMs[key] ?: return false
        if (clockMs() >= until) {
            gatedUntilMs.remove(key, until)
            return false
        }
        return true
    }

    fun gatedClients(): Set<String> {
        val now = clockMs()
        gatedUntilMs.entries.removeAll { it.value <= now }
        return gatedUntilMs.keys.toSet()
    }

    fun clear() {
        gatedUntilMs.clear()
        refusalStrikes.clear()
    }

    companion object {
        const val DEFAULT_TTL_MS = 30L * 60L * 1000L
        const val REFUSALS_BEFORE_DEMOTION = 2
    }
}

/**
 * Per-client HTTP header & User-Agent contract for googlevideo requests.
 * Non-web clients (VISIONOS, ANDROID_VR, ANDROID, IOS) must NOT send browser
 * Origin/Referer/Sec-Fetch-* headers; media byte ranges must use identity encoding.
 */
object YouTubeRequestPolicy {
    private val WEB_FAMILY_CLIENTS = setOf(
        "WEB",
        "MWEB",
        "WEB_REMIX",
        "WEB_CREATOR",
        "WEB_EMBEDDED_PLAYER",
        "TVHTML5",
        "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
    )

    const val UA_VISIONOS_APP =
        "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)"
    const val UA_VISIONOS_WEB =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    const val UA_TV_TIZEN =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 " +
            "(KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"
    const val UA_ANDROID_VR =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    const val UA_ANDROID =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
    const val UA_IOS =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    const val UA_MWEB =
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"

    fun userAgentForClient(clientName: String?, fallback: String): String =
        when (clientName?.uppercase()) {
            "VISIONOS" -> fallback.ifBlank { UA_VISIONOS_APP }
            "ANDROID_VR" -> UA_ANDROID_VR
            "ANDROID", "ANDROID_CREATOR" -> UA_ANDROID
            "IOS" -> UA_IOS
            "TVHTML5" -> UA_TV_TIZEN
            "MWEB" -> UA_MWEB
            "WEB", "WEB_REMIX" -> UA_WEB
            else -> fallback.ifBlank { UA_VISIONOS_APP }
        }

    fun headersForClient(
        clientName: String?,
        allowGzip: Boolean = false,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        if (!allowGzip) headers["Accept-Encoding"] = "identity"
        headers["Accept"] = "*/*"
        val client = clientName?.uppercase()
        if (client != null && client in WEB_FAMILY_CLIENTS) {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com/"
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }
        return headers
    }
}

/**
 * Routes around unreachable googlevideo edge servers by rewriting the host to
 * the next mirror listed in the URL's `mn=` query parameter (Flow #1974).
 */
class YouTubeHostFallback(
    private val forgetAfterMs: Long = FORGET_AFTER_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val unreachable = ConcurrentHashMap<String, Long>()

    fun markUnreachable(url: String) {
        val now = clockMs()
        // Entries only leave on a later lookup of the same host; googlevideo hostnames rarely repeat, so sweep here.
        if (unreachable.size > MAX_TRACKED_HOSTS) unreachable.values.removeAll { now - it >= forgetAfterMs }
        hostOf(url)?.let { unreachable[it] = now }
    }

    fun route(url: String): String? {
        val host = hostOf(url) ?: return url
        if (!isUnreachable(host)) return url
        val prefix = host.substringBefore(SERVER_SEPARATOR, missingDelimiterValue = "")
        if (prefix.isEmpty() || !host.endsWith(GOOGLEVIDEO_SUFFIX)) return null
        return servers(url)
            .map { "$prefix$SERVER_SEPARATOR$it$GOOGLEVIDEO_SUFFIX" }
            .firstOrNull { it != host && !isUnreachable(it) }
            ?.let { url.replaceFirst("://$host/", "://$it/") }
    }

    private fun isUnreachable(host: String): Boolean {
        val markedAt = unreachable[host] ?: return false
        if (clockMs() - markedAt < forgetAfterMs) return true
        unreachable.remove(host, markedAt)
        return false
    }

    private fun servers(url: String): List<String> =
        YouTubeStreamDenial.queryParam(url, "mn")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    companion object {
        const val FORGET_AFTER_MS = 10L * 60L * 1000L
        private const val MAX_TRACKED_HOSTS = 128
        private const val SERVER_SEPARATOR = "---"
        private const val GOOGLEVIDEO_SUFFIX = ".googlevideo.com"
    }
}

/** Parses `signatureCipher` / `cipher` query strings into URL + scrambled sig + target param. */
internal data class YouTubeSignatureCipher(
    val url: String,
    val signature: String,
    val parameter: String,
) {
    fun signedUrl(solvedSignature: String): String {
        val sep = if ('?' in url) '&' else '?'
        return "$url$sep$parameter=${URLEncoder.encode(solvedSignature, "UTF-8")}"
    }

    companion object {
        private const val DEFAULT_PARAMETER = "signature"

        fun parse(cipher: String?): YouTubeSignatureCipher? {
            if (cipher.isNullOrBlank()) return null
            val fields = cipher.split('&').mapNotNull { pair ->
                val sep = pair.indexOf('=')
                if (sep <= 0) return@mapNotNull null
                val k = pair.substring(0, sep)
                val v = try {
                    URLDecoder.decode(pair.substring(sep + 1), "UTF-8")
                } catch (_: IllegalArgumentException) {
                    pair.substring(sep + 1)
                }
                k to v
            }.toMap()
            val url = fields["url"]?.takeIf { it.isNotBlank() } ?: return null
            val sig = fields["s"]?.takeIf { it.isNotBlank() } ?: return null
            return YouTubeSignatureCipher(url, sig, fields["sp"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PARAMETER)
        }
    }
}

/**
 * Computes GVS pre-roll hold window for TV_TIZEN answers that carry a start ad placement
 * (Flow measured 2026-10-08: 0.8x skipOffsetMilliseconds, capped at 8s).
 */
internal object YouTubePrerollWait {
    const val MAX_WAIT_MS = 8_000L
    private const val SHARE_OF_SKIP_OFFSET = 0.8
    private const val START_PLACEMENT = "AD_PLACEMENT_KIND_START"

    fun of(adPlacements: JsonArray?): Long {
        val skipOffsets = adPlacements.orEmpty()
            .mapNotNull { (it as? JsonObject)?.child("adPlacementRenderer") }
            .filter { it.child("config")?.child("adPlacementConfig")?.text("kind") == START_PLACEMENT }
            .map { renderer ->
                renderer.child("renderer")
                    ?.child("instreamVideoAdRenderer")
                    ?.text("skipOffsetMilliseconds")
                    ?.toLongOrNull()
                    ?.let { (it * SHARE_OF_SKIP_OFFSET).toLong() }
                    ?: MAX_WAIT_MS
            }
        return skipOffsets.maxOrNull()?.coerceAtMost(MAX_WAIT_MS) ?: 0L
    }

    private fun JsonElement.child(name: String): JsonObject? = (this as? JsonObject)?.get(name) as? JsonObject
    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.content
}
