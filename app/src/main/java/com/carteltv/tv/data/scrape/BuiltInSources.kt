package com.carteltv.tv.data.scrape

import android.util.Log
import com.carteltv.tv.data.debrid.TorboxClient
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.stremio.StremioClient
import com.carteltv.tv.data.stremio.matchesMovie
import com.carteltv.tv.data.stremio.matchesSeries
import com.carteltv.tv.core.ScatterGatherLimits
import com.carteltv.tv.core.scatterGather
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Searches public torrent indexes directly, so sources exist without any Stremio addon.
 * Results come back as ordinary [StremioClient.StreamTuple]s and go through the same
 * debrid bridge as addon torrents.
 */
object BuiltInSources {
    private val SOURCES: List<ScrapeSource> = listOf(YtsSource, EztvSource, PirateBaySource)
    private const val TAG = "BuiltInSources"
    private const val MAX_CHECKED = 100
    private const val MAX_RESULTS = 30

    private val EPISODE_MARK = Regex("""(?i)\bs\d{1,2}e\d{1,3}|\b\d{1,2}x\d{2,3}\b""")

    suspend fun search(request: ScrapeRequest, sources: List<ScrapeSource> = SOURCES): List<StremioClient.StreamTuple> = withContext(Dispatchers.IO) {
        if (request.isSeries && (request.season == null || request.episode == null)) return@withContext emptyList()
        val jobs = sources.filter { it.applies(request) }.map { s -> suspend { logged(s, request) } to false }
        val found = scatterGather(jobs, limits = ScatterGatherLimits(concurrency = 4))
        val candidates = candidates(request, found).take(MAX_CHECKED)
        Log.i(TAG, "${request.title}: ${found.size} releases found, ${candidates.size} match the title")
        if (candidates.isEmpty()) return@withContext emptyList()

        val torbox = AppPreferences.torboxToken.takeIf { it.isNotBlank() }
        val cached = torbox?.let { suspendRunCatching { TorboxClient(it).cachedHashes(candidates.map { c -> c.hash }) }.getOrNull() }
        if (torbox != null && cached == null) {
            Log.w(TAG, "TorBox cache check failed; showing nothing rather than results that may need a download")
            return@withContext emptyList()
        }
        Log.i(TAG, "${cached?.size ?: 0} of ${candidates.size} are cached on TorBox")
        toStreams(request, candidates, cached, keepUncached = torbox == null)
    }

    private suspend fun logged(source: ScrapeSource, request: ScrapeRequest): List<Release> = try {
        source.search(request).also { Log.i(TAG, "${source.id}: ${it.size} releases") }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "${source.id} failed: ${HttpSupport.redact(e.message)}")
        throw e
    }

    internal fun accepts(request: ScrapeRequest, name: String): Boolean =
        !ReleaseQuality.isJunk(name, request.title) && if (request.isSeries) {
            EPISODE_MARK.containsMatchIn(name) &&
                matchesSeries(request.title, name, request.season, request.episode, request.aliases)
        } else {
            matchesMovie(request.title, name, request.year, request.aliases)
        }

    /** Releases of the requested title only, one per info hash (the best-seeded copy), best-seeded first. */
    internal fun candidates(request: ScrapeRequest, found: List<Release>): List<Release> =
        found.filter { accepts(request, it.name) }
            .groupBy { it.hash }
            .map { (_, copies) -> copies.maxBy { it.seeders } }
            .sortedByDescending { it.seeders }

    /** The catalogue title, not the release name: "Fight Club (1999)" or "Show S01E02". */
    internal fun displayTitle(request: ScrapeRequest): String = when {
        request.isSeries && request.season != null && request.episode != null ->
            "${request.title} S%02dE%02d".format(request.season, request.episode)
        request.year != null -> "${request.title} (${request.year})"
        else -> request.title
    }

    /**
     * Best first: cached, then resolution, source (remux down to DVD), HDR, seeders.
     * [cached] null means the cache check did not run, so nothing is marked and nothing is hidden.
     */
    internal fun toStreams(
        request: ScrapeRequest,
        candidates: List<Release>,
        cached: Set<String>?,
        keepUncached: Boolean,
    ): List<StremioClient.StreamTuple> =
        candidates
            .filter { keepUncached || cached?.contains(it.hash) != false }
            .sortedWith(
                compareByDescending<Release> { cached?.contains(it.hash) == true }
                    .thenByDescending { ReleaseQuality.resolutionRank(it.name) }
                    .thenByDescending { ReleaseQuality.sourceRank(it.name) }
                    .thenByDescending { ReleaseQuality.hasHdr(it.name) }
                    .thenByDescending { it.seeders },
            )
            .take(MAX_RESULTS)
            .map { r ->
                val isCached = cached?.contains(r.hash) == true
                StremioClient.StreamTuple(
                    title = displayTitle(request),
                    infoHash = r.hash,
                    sources = PUBLIC_TRACKERS.map { "tracker:$it" },
                    behaviorHints = JsonObject(mapOf("filename" to JsonPrimitive(r.name))),
                    name = "Built-in · ${r.source}",
                    description = listOfNotNull(r.source, ReleaseQuality.group(r.name)).joinToString(" · "),
                    cached = isCached,
                    badges = badges(r, isCached),
                )
            }

    internal fun badges(r: Release, cached: Boolean): List<String> = listOfNotNull(
        if (cached) "⚡ TorBox" else null,
        ReleaseQuality.resolutionLabel(r.name),
        ReleaseQuality.sourceLabel(r.name),
        ReleaseQuality.hdrLabel(r.name),
        ReleaseQuality.codecLabel(r.name),
        ReleaseQuality.audioLabel(r.name),
        if (ReleaseQuality.isMultiAudio(r.name)) "MULTI" else null,
        formatSize(r.sizeBytes),
        "👤 ${r.seeders}",
    )

    private fun formatSize(bytes: Long): String? = when {
        bytes <= 0 -> null
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        else -> "%d MB".format(bytes shr 20)
    }
}
