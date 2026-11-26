package com.carteltv.tv.data.network

import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Shared JSON codecs, HTTP plumbing, and digest helpers used across every data client.
 * Keeps one instance of each serializer configuration and one HttpURLConnection path.
 */
object HttpSupport {
    val json: Json = Json { ignoreUnknownKeys = true }
    val lenientJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun normalizeBearerToken(raw: String): String =
        raw.trim().removePrefix("Bearer ").trim()

    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private val SECRET_PARAM = Regex("(?i)([?&](?:password|pass|pwd|api_key|apikey|token|key|secret|k|t)=)[^&\\s\"']+")

    /** HttpURLConnection puts the full request URL in its messages; strip credential query values before they reach UI or logs. */
    fun redact(message: String?): String = SECRET_PARAM.replace(message.orEmpty(), "$1[hidden]")

    @Volatile var transport: HttpTransport = OkHttpTransport

    /** Synchronous GET on the calling thread (for worker threads or inside IO blocks). */
    fun getBlocking(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 8_000,
        readMs: Int = 15_000,
    ): String = transport.getBlocking(url, headers, connectMs, readMs)

    /** Coroutine GET on Dispatchers.IO. Throws on non-2xx or transport errors. */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 8_000,
        readMs: Int = 15_000,
    ): String = transport.get(url, headers, connectMs, readMs)

    /** Coroutine binary GET on Dispatchers.IO (map tiles, images). Throws on non-2xx or transport errors. */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 8_000,
        readMs: Int = 15_000,
    ): ByteArray = transport.getBytes(url, headers, connectMs, readMs)

    /** Coroutine HTTP request returning (statusCode, responseOrErrorBody) without throwing on 4xx/5xx. */
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        connectMs: Int = 10_000,
        readMs: Int = 30_000,
    ): Pair<Int, String> = transport.request(url, method, headers, body, contentType, connectMs, readMs)

    /** Coroutine HTTP request returning (statusCode, responseHeaders, responseOrErrorBody) without throwing on 4xx/5xx. */
    suspend fun requestFull(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        connectMs: Int = 10_000,
        readMs: Int = 30_000,
    ): Triple<Int, Map<String, List<String>>, String> =
        transport.requestFull(url, method, headers, body, contentType, connectMs, readMs)

    /** Streaming GET for large payloads. Caller must close the returned body. */
    suspend fun getStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 10_000,
        readMs: Int = 30_000,
    ): HttpResponseStream = transport.getStream(url, headers, connectMs, readMs)
}

