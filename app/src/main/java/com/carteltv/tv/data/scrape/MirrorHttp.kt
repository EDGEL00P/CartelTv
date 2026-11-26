package com.carteltv.tv.data.scrape

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.network.OkHttpTransport
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/**
 * GET against a source that lives on several domains and is often blocked. For each mirror it tries
 * the normal route, then the same URL with DNS-over-HTTPS; the mirror that last worked goes first.
 */
object MirrorHttp {
    internal const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private const val CONNECT_MS = 6_000
    private const val READ_MS = 12_000

    private val lastGood = ConcurrentHashMap<String, String>()

    suspend fun get(sourceId: String, mirrors: List<String>, path: String, accept: String = "application/json"): String {
        require(mirrors.isNotEmpty()) { "no mirrors for $sourceId" }
        val headers = mapOf("User-Agent" to BROWSER_UA, "Accept" to accept)
        val ordered = lastGood[sourceId]?.let { first -> listOf(first) + mirrors.filterNot { it == first } } ?: mirrors
        val failures = mutableListOf<String>()
        for (base in ordered) {
            val url = base.trimEnd('/') + path
            val body = attempt(failures, base) { HttpSupport.get(url, headers, CONNECT_MS, READ_MS) }
                ?: attempt(failures, "$base via DoH") { dohGet(url, headers) }
            if (body != null) {
                lastGood[sourceId] = base
                return body
            }
        }
        throw IOException("$sourceId unreachable: ${failures.joinToString("; ")}")
    }

    private suspend fun attempt(failures: MutableList<String>, label: String, call: suspend () -> String): String? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failures += "$label → ${HttpSupport.redact(e.message)}"
        null
    }

    private suspend fun dohGet(url: String, headers: Map<String, String>): String {
        val client = OkHttpTransport.clientWithDns(DohDns, CONNECT_MS, READ_MS)
        val request = try {
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid request")
        }
        val response = await(client.newCall(request))
        return response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) response.close() else cont.resume(response)
            }
        })
    }
}
