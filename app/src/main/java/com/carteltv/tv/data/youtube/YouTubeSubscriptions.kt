package com.carteltv.tv.data.youtube
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Subscriptions kept on the box: no account needed. The feed is each channel's public upload list,
 * merged newest first, so it works the same signed in or out.
 */
object YouTubeSubscriptions {

    @Serializable
    data class Sub(val id: String, val name: String, val avatar: String? = null)

    private const val MAX_SUBS = 60
    private const val TTL_MS = 10 * 60_000L
    private const val FEED_CAP = 120

    private data class SubCache(val raw: String?, val entries: List<Sub>)
    @Volatile private var subCache = SubCache(null, emptyList())

    private fun entries(): List<Sub> {
        val raw = AppPreferences.youTubeSubsRaw
        val cur = subCache
        if (cur.raw == raw) return cur.entries
        val parsed = raw.takeIf { it.isNotBlank() }
            ?.let { runCatching { HttpSupport.json.decodeFromString<List<Sub>>(it) }.getOrNull() }
            .orEmpty()
        subCache = SubCache(raw, parsed)
        return parsed
    }

    fun all(): List<Sub> = entries()

    fun isSubscribed(id: String?): Boolean = id != null && entries().any { it.id == id }

    /** Subscribes, or unsubscribes if already subscribed. Returns true when now subscribed. */
    fun toggle(sub: Sub): Boolean {
        val cur = all()
        val now = if (cur.any { it.id == sub.id }) cur.filterNot { it.id == sub.id }
        else (listOf(sub) + cur).take(MAX_SUBS)
        AppPreferences.youTubeSubsRaw = HttpSupport.json.encodeToString(now)
        invalidate()
        return now.any { it.id == sub.id }
    }

    @Volatile private var cache: List<YouTubeApiClient.YouTubeVideo> = emptyList()
    @Volatile private var cacheAt = 0L
    @Volatile private var cacheKey = ""

    private fun invalidate() { cacheAt = 0L }

    suspend fun feed(force: Boolean = false): List<YouTubeApiClient.YouTubeVideo> {
        val subs = all()
        val key = (if (YouTubeSession.signedIn()) "signed|" else "") + subs.joinToString(",") { it.id }
        val now = System.currentTimeMillis()
        if (subs.isEmpty() && !YouTubeSession.signedIn()) { cache = emptyList(); cacheKey = key; return emptyList() }
        if (!force && key == cacheKey && cache.isNotEmpty() && now - cacheAt < TTL_MS) return cache
        val gate = Semaphore(6)
        val lists = coroutineScope {
            subs.map { s ->
                async {
                    gate.withPermit {
                        suspendRunCatching { parseFeed(HttpSupport.get("https://www.youtube.com/feeds/videos.xml?channel_id=${s.id}")) }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }
        val local = merge(lists)
        // Signed in: the account's own subscription feed leads (already newest first); local-only
        // channels follow. Falls back to the local merge when the account request fails.
        cache = if (YouTubeSession.signedIn()) {
            val signed = suspendRunCatching { YouTubeApiClient.subscriptionsFeed() }.getOrNull().orEmpty()
            if (signed.isNotEmpty()) {
                val seen = signed.map { it.videoId }.toSet()
                signed + local.filter { it.videoId !in seen }
            } else local
        } else local
        cacheKey = key
        cacheAt = System.currentTimeMillis()
        return cache
    }

    /** Newest first, one entry per video, capped. */
    internal fun merge(lists: List<List<YouTubeApiClient.YouTubeVideo>>): List<YouTubeApiClient.YouTubeVideo> =
        lists.flatten().distinctBy { it.videoId }.sortedByDescending { it.publishedMs }.take(FEED_CAP)

    private val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)

    private fun tag(block: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim()

    internal fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    private fun parseTime(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso)?.time ?: 0L }.getOrDefault(0L)
    }

    /** Videos in a channel's Atom feed. Shorts are left out: they are links to /shorts/, not full videos. */
    internal fun parseFeed(xml: String): List<YouTubeApiClient.YouTubeVideo> =
        ENTRY.findAll(xml).mapNotNull { m ->
            val b = m.groupValues[1]
            if (b.contains("youtube.com/shorts/")) return@mapNotNull null
            val id = tag(b, "yt:videoId") ?: return@mapNotNull null
            val title = tag(b, "title")?.let(::unescape)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val author = Regex("<author>.*?<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::unescape)
            val thumb = Regex("<media:thumbnail url=\"(.*?)\"").find(b)?.groupValues?.get(1) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            YouTubeApiClient.YouTubeVideo(
                videoId = id, title = title, channel = author, duration = null, thumb = thumb,
                channelId = tag(b, "yt:channelId"), publishedMs = parseTime(tag(b, "published")),
            )
        }.toList()

    /** "5 min ago", "3 h ago", "2 d ago": the age shown beside a channel name. */
    fun ago(publishedMs: Long, nowMs: Long = System.currentTimeMillis()): String {        if (publishedMs <= 0L) return ""
        val min = ((nowMs - publishedMs) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            min < 60 * 24 * 60 -> "${min / (24 * 60)} d ago"
            else -> "${min / (30 * 24 * 60)} mo ago"
        }
    }

    /** "26 Nov 2026 · 14:32" — the exact upload stamp, in the viewer's locale. */
    fun uploadedAt(publishedMs: Long): String {
        if (publishedMs <= 0L) return ""
        return SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(java.util.Date(publishedMs))
    }
}
