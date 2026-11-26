package com.carteltv.tv.data.network

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Opportunistic HTTPS: idempotent GETs to public hostnames on the default port try `https://` first and
 * fall back to the original `http://` URL on any failure, so providers that only speak plain HTTP keep working.
 * Never touches POSTs, LAN/loopback/IP-literal hosts or explicit ports, where an HTTPS twin would not exist.
 */
class HttpsUpgradeTransport(
    private val inner: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
) : HttpTransport {

    /** Host -> time until which HTTPS is known not to work, so a dead 443 costs one probe per TTL, not per call. */
    private val httpOnlyUntil = ConcurrentHashMap<String, Long>()

    private fun candidate(url: String): String? {
        val host = upgradeHost(url) ?: return null
        val until = httpOnlyUntil[host]
        if (until != null && until > now()) return null
        return "https" + url.substring(4)
    }

    private fun failed(url: String) {
        upgradeHost(url)?.let { httpOnlyUntil[it] = now() + HTTP_ONLY_TTL_MS }
    }

    private fun succeeded(url: String) {
        upgradeHost(url)?.let { httpOnlyUntil.remove(it) }
    }

    private fun probe(connectMs: Int) = minOf(connectMs, PROBE_CONNECT_MS)

    override fun getBlocking(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): String {
        val https = candidate(url) ?: return inner.getBlocking(url, headers, connectMs, readMs)
        return try {
            inner.getBlocking(https, headers, probe(connectMs), readMs).also { succeeded(url) }
        } catch (_: Exception) {
            failed(url)
            inner.getBlocking(url, headers, connectMs, readMs)
        }
    }

    override suspend fun get(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): String {
        val https = candidate(url) ?: return inner.get(url, headers, connectMs, readMs)
        return try {
            inner.get(https, headers, probe(connectMs), readMs).also { succeeded(url) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed(url)
            inner.get(url, headers, connectMs, readMs)
        }
    }

    override suspend fun getBytes(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): ByteArray {
        val https = candidate(url) ?: return inner.getBytes(url, headers, connectMs, readMs)
        return try {
            inner.getBytes(https, headers, probe(connectMs), readMs).also { succeeded(url) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed(url)
            inner.getBytes(url, headers, connectMs, readMs)
        }
    }

    override suspend fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
        connectMs: Int,
        readMs: Int,
    ): Pair<Int, String> {
        if (method != "GET" || body != null) return inner.request(url, method, headers, body, contentType, connectMs, readMs)
        val https = candidate(url) ?: return inner.request(url, method, headers, body, contentType, connectMs, readMs)
        val (code, text) = inner.request(https, method, headers, null, contentType, probe(connectMs), readMs)
        if (code > 0) {
            succeeded(url)
            return code to text
        }
        failed(url)
        return inner.request(url, method, headers, null, contentType, connectMs, readMs)
    }

    override suspend fun requestFull(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
        connectMs: Int,
        readMs: Int,
    ): Triple<Int, Map<String, List<String>>, String> {
        if (method != "GET" || body != null) return inner.requestFull(url, method, headers, body, contentType, connectMs, readMs)
        val https = candidate(url) ?: return inner.requestFull(url, method, headers, body, contentType, connectMs, readMs)
        val result = inner.requestFull(https, method, headers, null, contentType, probe(connectMs), readMs)
        if (result.first > 0) {
            succeeded(url)
            return result
        }
        failed(url)
        return inner.requestFull(url, method, headers, null, contentType, connectMs, readMs)
    }

    override suspend fun getStream(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): HttpResponseStream {
        val https = candidate(url) ?: return inner.getStream(url, headers, connectMs, readMs)
        return try {
            inner.getStream(https, headers, probe(connectMs), readMs).also { succeeded(url) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            failed(url)
            inner.getStream(url, headers, connectMs, readMs)
        }
    }

    companion object {
        private const val PROBE_CONNECT_MS = 3_000
        private const val HTTP_ONLY_TTL_MS = 6 * 60 * 60 * 1000L

        /** Lower-case hostname if [url] is an upgradable plain-HTTP URL, else null. */
        internal fun upgradeHost(url: String): String? {
            if (!url.startsWith("http://", ignoreCase = true)) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.port != -1 || uri.userInfo != null) return null
            val host = uri.host?.lowercase() ?: return null
            if ('.' !in host || ':' in host) return null
            if (host.all { it.isDigit() || it == '.' }) return null
            if (PRIVATE_SUFFIXES.any { host.endsWith(it) }) return null
            return host
        }

        private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")
    }
}
