package com.carteltv.tv.data.youtube
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Optional

/** Return YouTube Dislike: community estimate of dislikes, from returnyoutubedislikeapi.com. */
object YouTubeDislikes {

    data class Votes(val likes: Long, val dislikes: Long) {
        val label: String get() = "${compact(likes)} likes · ${compact(dislikes)} dislikes"
    }

    private val cache = object : LinkedHashMap<String, Optional<Votes>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Optional<Votes>>?) = size > 128
    }

    /** Null on any failure or for a deleted video; failures are not cached. */
    suspend fun votes(videoId: String): Votes? {
        synchronized(cache) { cache[videoId] }?.let { return it.orElse(null) }
        val parsed = withContext(Dispatchers.IO) {
            suspendRunCatching {
                val raw = HttpSupport.get("https://returnyoutubedislikeapi.com/votes?videoId=$videoId", connectMs = 4_000, readMs = 4_000)
                Optional.ofNullable(parse(raw))
            }.getOrNull()
        } ?: return null
        synchronized(cache) { cache[videoId] = parsed }
        return parsed.orElse(null)
    }

    internal fun parse(raw: String): Votes? {
        val o: JsonObject = runCatching { HttpSupport.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if ((o["deleted"] as? JsonPrimitive)?.content == "true") return null
        val likes = (o["likes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        val dislikes = (o["dislikes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        return Votes(likes, dislikes)
    }

    internal fun compact(n: Long): String = when {
        n >= 1_000_000_000 -> trim(n / 1e9) + "B"
        n >= 1_000_000 -> trim(n / 1e6) + "M"
        n >= 1_000 -> trim(n / 1e3) + "K"
        else -> n.toString()
    }

    private fun trim(d: Double): String {
        val s = "%.1f".format(java.util.Locale.US, d)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
