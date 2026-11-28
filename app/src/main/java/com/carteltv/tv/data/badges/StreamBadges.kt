package com.carteltv.tv.data.badges

import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Nuvio-compatible stream badges (nuvio.wiki/settings/badges): a badge set is a
 * JSON file of regex filters imported by URL, scanned against each stream's
 * title. The community sets (NardBadges, Elite, Minimalist…) drop in unchanged:
 * same schema, colors are Android `#AARRGGBB`, only one badge per `groupId`
 * shows per title, and the first matching filter wins — import order is the
 * priority order, exactly like Nuvio's reader.
 */
object StreamBadges {

    /** One matched badge, ready to render: label plus optional artwork and colors. */
    data class Badge(
        val name: String,
        val image: String?,
        val tagColor: Int?,      // ARGB int, from #AARRGGBB
        val borderColor: Int?,
    )

    @Serializable
    data class FilterDef(
        val id: String = "",
        val groupId: String = "",
        val name: String = "",
        val pattern: String = "",
        val imageURL: String? = null,
        val tagColor: String? = null,
        val borderColor: String? = null,
    )

    @Serializable
    private data class BadgeSet(val filters: List<FilterDef> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private data class CompiledFilter(val def: FilterDef, val regex: Regex)
    @Volatile private var compiled: List<CompiledFilter> = emptyList()
    private var loadedAt = 0L
    private const val FRESH_MS = 6 * 60 * 60_000L

    /** Fetches every imported set; silent per URL, so one dead link can't kill the rest. */
    suspend fun ensureLoaded() {
        if (System.currentTimeMillis() - loadedAt < FRESH_MS) return
        val urls = listOf(AppPreferences.streamBadgesUrl).filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            compiled = emptyList()
            loadedAt = System.currentTimeMillis()
            return
        }
        compiled = withContext(Dispatchers.IO) {
            urls.flatMap { url ->
                suspendRunCatching {
                    val txt = com.carteltv.tv.data.network.HttpSupport.get(
                        url, headers = mapOf("Accept" to "application/json"), connectMs = 10_000, readMs = 20_000,
                    )
                    json.decodeFromString<BadgeSet>(txt).filters
                }.getOrDefault(emptyList())
            }.mapNotNull { f ->
                runCatching { Regex(f.pattern) }.getOrNull()?.let { CompiledFilter(f, it) }
            }
        }
        loadedAt = System.currentTimeMillis()
    }

    /** Forces the next [ensureLoaded] to re-download — called when sets are imported or removed. */
    fun invalidate() { loadedAt = 0 }

    /** Validates one URL without caching it; used when importing from Settings. */
    suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val txt = com.carteltv.tv.data.network.HttpSupport.get(
                url, headers = mapOf("Accept" to "application/json"), connectMs = 10_000, readMs = 20_000,
            )
            json.decodeFromString<BadgeSet>(txt).filters.isNotEmpty()
        }.getOrDefault(false)
    }

    /** Badges for one stream: scanned over everything the addon wrote about it. */
    fun forTitle(title: String?): List<Badge> = matchCompiled(compiled, title)

    internal fun match(filters: List<FilterDef>, title: String?): List<Badge> =
        matchCompiled(filters.mapNotNull { f -> runCatching { Regex(f.pattern) }.getOrNull()?.let { CompiledFilter(f, it) } }, title)

    private fun matchCompiled(filters: List<CompiledFilter>, title: String?): List<Badge> {
        if (title.isNullOrBlank() || filters.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<Badge>()
        for ((f, rx) in filters) {
            if (f.groupId in seen) continue
            if (rx.containsMatchIn(title)) {
                seen += f.groupId
                out += Badge(f.name, f.imageURL, color(f.tagColor), color(f.borderColor))
                if (out.size >= 6) break
            }
        }
        return out
    }

    /** `#AARRGGBB` → ARGB int; null (or unparsable) means the UI falls back to its own tokens. */
    internal fun color(raw: String?): Int? =
        raw?.removePrefix("#")?.toLongOrNull(16)?.let { it.toInt() }
}
