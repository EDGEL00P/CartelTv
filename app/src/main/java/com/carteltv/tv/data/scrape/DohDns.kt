package com.carteltv.tv.data.scrape

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.network.OkHttpTransport
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dns
import okhttp3.Request

/**
 * Resolves names through Cloudflare DNS-over-HTTPS, reached by IP so it does not depend on the
 * resolver that is failing. Used only when the system resolver cannot reach a source domain.
 */
object DohDns : Dns {
    private const val ENDPOINT = "https://1.1.1.1/dns-query"
    private const val TYPE_A = 1

    // The house client's pool with a short leash; the DOH endpoint is a literal IP, so this never recurses.
    private val resolver by lazy {
        OkHttpTransport.clientWithDns(this, 4_000, 4_000)
    }
    private val answers = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        answers[hostname]?.let { return it }
        val request = Request.Builder()
            .url("$ENDPOINT?name=$hostname&type=A")
            .header("Accept", "application/dns-json")
            .build()
        val body = try {
            resolver.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        } catch (e: java.io.IOException) {
            throw UnknownHostException("$hostname (DoH: ${e.message})")
        }
        val found = parseAnswers(body).map { InetAddress.getByName(it) }
        if (found.isEmpty()) throw UnknownHostException(hostname)
        answers[hostname] = found
        return found
    }

    internal fun parseAnswers(body: String): List<String> {
        val root = runCatching { HttpSupport.lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val list = root["Answer"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = (o["type"] as? JsonPrimitive)?.content?.toIntOrNull()
            val data = (o["data"] as? JsonPrimitive)?.content
            data?.takeIf { type == TYPE_A && IPV4.matches(it) }
        }
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
