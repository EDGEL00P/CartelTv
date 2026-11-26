package com.carteltv.tv.engine.playback

import java.net.HttpURLConnection
import java.net.URL

/**
 * Fail-open check of a remembered stream link. Only a positive "gone" answer invalidates it; a network error,
 * a timeout, a refused HEAD (405) or a rate limit (429) all count as alive, so a flaky check never blocks playback.
 */
object StreamLinkCheck {
    private val GONE = setOf(400, 401, 403, 404, 410)

    fun isGone(statusCode: Int): Boolean = statusCode in GONE

    /**
     * Whether a link is worth probing before playback. Live and recorded items and the provider's own host are not:
     * a request there can use up the account's connection slot or means nothing.
     */
    fun shouldProbe(url: String, refId: String, providerBase: String?): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (refId.startsWith("live:") || refId.startsWith("recording:")) return false
        return providerBase.isNullOrBlank() || !url.startsWith(providerBase)
    }

    /** Blocking HEAD request, redirects followed. Call off the main thread. */
    fun isAlive(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 3_000): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            !isGone(conn.responseCode)
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        true
    }
}
