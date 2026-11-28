package com.carteltv.tv.data.network

import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Address
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A streaming HTTP response. Closing the stream releases the underlying connection. */
class HttpResponseStream(
    val code: Int,
    val headers: Map<String, List<String>>,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

/** Seam for every outbound HTTP call so clients can be driven by a fake in tests. */
interface HttpTransport {
    fun getBlocking(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): String
    suspend fun get(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): String
    suspend fun getBytes(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): ByteArray
    /** Streaming GET for large payloads (XMLTV, playlists). Caller must close the returned body. */
    suspend fun getStream(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): HttpResponseStream
    suspend fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
        connectMs: Int,
        readMs: Int,
    ): Pair<Int, String>
    /** Like [request] but also returns response headers, needed for ETag/Last-Modified validators. */
    suspend fun requestFull(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
        connectMs: Int,
        readMs: Int,
    ): Triple<Int, Map<String, List<String>>, String>
}

/** One pooled OkHttp client behind [Net]; per-call timeouts derive from it so connections are shared. */
object OkHttpTransport : HttpTransport {
    // Open-Meteo answers 403 to Android's default "Dalvik/…" agent, so every call identifies the app.
    internal const val USER_AGENT = "CartelTV/1.0 (Android TV launcher)"

    // Cleartext and older TLS stay allowed: IPTV providers and LAN devices are user-supplied.
    /** Set from Application.onCreate; enables a 50 MB on-disk HTTP cache that honours server cache headers. */
    @Volatile
    var cacheDir: File? = null

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .eventListenerFactory { HttpEventListener() }
            .apply { cacheDir?.let { cache(Cache(File(it, "http"), 50L * 1024 * 1024)) } }
            .build()
    }

    /** Shares the connection pool and dispatcher but not the HTTP cache: Coil keeps its own disk cache. */
    fun imageClient(): OkHttpClient = base.newBuilder().cache(null).build()

    /** Same pool and timeouts as a normal call, but names resolve through [dns]. */
    fun clientWithDns(dns: okhttp3.Dns, connectMs: Int, readMs: Int): OkHttpClient =
        client(connectMs, readMs).newBuilder().dns(dns).build()

    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    private fun client(connectMs: Int, readMs: Int): OkHttpClient =
        clients.getOrPut((connectMs.toLong() shl 32) or readMs.toLong()) {
            base.newBuilder()
                .connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
        }

    private fun build(url: String, headers: Map<String, String>): Request.Builder =
        try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT).apply { headers.forEach { (k, v) -> header(k, v) } }
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid request: ${HttpSupport.redact(e.message)}")
        }

    /** Redacts credentials from the URL in every message that can reach the UI or logs. */
    private fun failure(url: String, started: Long, code: Int, cause: String?, body: String = ""): HttpFailure {
        android.util.Log.w(
            "Net",
            "HTTP $code after ${(System.nanoTime() - started) / 1_000_000} ms: ${HttpSupport.redact(url).take(120)} body=${body.take(200)}",
        )
        return HttpFailure(code, "HTTP $code${cause?.let { ": ${HttpSupport.redact(it)}" }.orEmpty()}")
    }

    private fun <T> Response.readOk(url: String, started: Long, read: (Response) -> T): T = use {
        if (!it.isSuccessful) throw failure(url, started, it.code, it.message, runCatching { it.body?.string().orEmpty() }.getOrDefault(""))
        read(it)
    }

    private fun executeOrFail(url: String, call: Call, started: Long): Response = try {
        call.execute()
    } catch (e: IOException) {
        throw failure(url, started, -1, e.message)
    }

    override fun getBlocking(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): String {
        // A transient 5xx or a dropped connection no longer costs the caller its whole result;
        // a working server's 4xx still fails at once (see HttpRetryPolicy).
        val retry = HttpRetryPolicy()
        while (true) {
            val started = System.nanoTime()
            val call = client(connectMs, readMs).newCall(build(url, headers).build())
            try {
                return executeOrFail(url, call, started).readOk(url, started) { it.body?.string().orEmpty() }
            } catch (e: IOException) {
                val delayMs = retry.nextDelayMs(e) ?: throw e
                Thread.sleep(delayMs) // bughunt:ignore kt-thread-sleep
            }
        }
    }

    override suspend fun get(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): String =
        fetch(url, headers, connectMs, readMs) { it.body?.string().orEmpty() }

    override suspend fun getBytes(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): ByteArray =
        fetch(url, headers, connectMs, readMs) { it.body?.bytes() ?: ByteArray(0) }

    private suspend fun <T> fetch(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int, read: (Response) -> T): T {
        val retry = HttpRetryPolicy()
        while (true) {
            val started = System.nanoTime()
            val call = client(connectMs, readMs).newCall(build(url, headers).build())
            try {
                val response = await(call) { failure(url, started, HttpFailure.NETWORK_FAILURE_CODE, it.message) }
                // The callback resumes on the caller's dispatcher (often main); draining the body must not happen there.
                return withContext(Dispatchers.IO) { response.readOk(url, started, read) }
            } catch (e: IOException) {
                // delay (not sleep) so a cancelled caller stops mid-backoff instead of retrying.
                val delayMs = retry.nextDelayMs(e) ?: throw e
                kotlinx.coroutines.delay(delayMs)
            }
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
        // HttpURLConnection turned a GET with a body into a POST; keep that.
        val verb = if (body != null && method.uppercase() in setOf("GET", "HEAD")) "POST" else method.uppercase()
        val payload = when {
            body != null -> body.toByteArray().toRequestBody(contentType?.toMediaTypeOrNull())
            verb in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        return try {
            val call = client(connectMs, readMs).newCall(build(url, headers).method(verb, payload).build())
            withContext(Dispatchers.IO) { await(call) { it }.use { it.code to it.body?.string().orEmpty() } }
        } catch (e: IOException) {
            -1 to HttpSupport.redact(e.message).ifBlank { "error" }
        }
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
        val verb = if (body != null && method.uppercase() in setOf("GET", "HEAD")) "POST" else method.uppercase()
        val payload = when {
            body != null -> body.toByteArray().toRequestBody(contentType?.toMediaTypeOrNull())
            verb in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        return try {
            val call = client(connectMs, readMs).newCall(build(url, headers).method(verb, payload).build())
            withContext(Dispatchers.IO) { await(call) { it }.use { response ->
                val hdrs = response.headers.toMultimap()
                Triple(response.code, hdrs, response.body?.string().orEmpty())
            } }
        } catch (e: IOException) {
            Triple(-1, emptyMap(), HttpSupport.redact(e.message).ifBlank { "error" })
        }
    }

    /** Converts OkHttp Headers (which may have multiple values per key) to a multimap. */
    private fun okhttp3.Headers.toMultimap(): Map<String, List<String>> =
        names().associateWith { name -> values(name) }

    override suspend fun getStream(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int): HttpResponseStream {
        val retry = HttpRetryPolicy()
        while (true) {
            val started = System.nanoTime()
            val call = client(connectMs, readMs).newCall(build(url, headers).build())
            try {
                val response = await(call) { failure(url, started, HttpFailure.NETWORK_FAILURE_CODE, it.message) }
                // The caller reads (and checks) the status from the returned stream, so only a
                // connection-level failure is retried here — a response that arrived is the caller's to judge.
                return HttpResponseStream(
                    code = response.code,
                    headers = response.headers.toMultimap(),
                    body = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0)),
                )
            } catch (e: IOException) {
                val delayMs = retry.nextDelayMs(e) ?: throw e
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }

    /** Suspends on [call]; cancelling the coroutine cancels the request. [onError] maps the IOException to the one thrown. */
    private suspend fun await(call: Call, onError: (IOException) -> IOException): Response =
        suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(onError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) response.close() else cont.resume(response)
                }
            })
        }
}

/** OkHttp EventListener that logs per-request timing, cache, and DNS metrics for observability.
 *
 * Logcat destination for now (Phase 2); a metrics backend can be wired in later without
 * changing the listener. All timings are off the main thread (OkHttp callbacks run on IO).
 */
class HttpEventListener : EventListener() {
    private val startedAt = ConcurrentHashMap<Call, Long>()

    override fun callStart(call: Call) {
        startedAt[call] = System.nanoTime()
        Log.d(
            "NetMetrics",
            "START ${call.request().method} ${HttpSupport.redact(call.request().url.toString()).take(120)}",
        )
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        val start = startedAt[call] ?: return
        val dnsMs = (System.nanoTime() - start) / 1_000_000
        Log.d("NetMetrics", "DNS $domainName ${dnsMs}ms (${inetAddressList.size} addrs)")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val start = startedAt[call] ?: return
        val ttfbMs = (System.nanoTime() - start) / 1_000_000
        val cache = if (response.cacheResponse != null) "HIT" else "MISS"
        Log.d(
            "NetMetrics",
            "HEADERS ${response.code} ${ttfbMs}ms cache=$cache " +
                "len=${response.body?.contentLength() ?: -1}B url=${HttpSupport.redact(call.request().url.toString()).take(100)}",
        )
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        val start = startedAt[call] ?: return
        val totalMs = (System.nanoTime() - start) / 1_000_000
        Log.d("NetMetrics", "COMPLETE ${byteCount}B ${totalMs}ms")
        startedAt.remove(call)
    }
}
