package com.carteltv.tv.data.stremio

import com.carteltv.tv.data.preferences.StreamPreferences
import com.carteltv.tv.data.scrape.ReleaseQuality

/**
 * Order and filtering for the stream picker, over both add-on and built-in sources.
 * Everything here is pure so the list a user sees is decided by one testable rule set.
 */
object StreamOrdering {

    enum class Sort { ORIGINAL, BEST, QUALITY, SIZE, SEEDERS, NAME }

    data class Filters(
        val resolutions: Set<String> = emptySet(),
        val hdrOnly: Boolean = false,
        val cachedOnly: Boolean = false,
        val hiddenSources: Set<String> = emptySet(),
        val regex: String? = null,
    )

    private val SIZE = Regex("""(?i)([\d.]+)\s*(GiB|GB|MiB|MB)""")
    private val SEEDERS = Regex("""(?i)(?:👤|👥|seeders?|seeds?)\D{0,3}(\d+)""")
    private val CACHED = Regex("""(?i)(?<!not )(?<!un)\bcached\b""")

    /** Resolution label for filtering: the parsed one, or "SD" when the name names none. */
    fun resolution(stream: StremioClient.StreamTuple): String =
        ReleaseQuality.resolutionLabel(stream.releaseText)

    fun sizeBytes(stream: StremioClient.StreamTuple): Long? {
        val match = SIZE.find(stream.releaseText) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val gb = match.groupValues[2].startsWith("G", ignoreCase = true)
        return (value * if (gb) (1L shl 30) else (1L shl 20)).toLong()
    }

    fun seeders(stream: StremioClient.StreamTuple): Int? =
        SEEDERS.find(stream.releaseText)?.groupValues?.get(1)?.toIntOrNull()

    fun isCached(stream: StremioClient.StreamTuple): Boolean =
        stream.cached || stream.badges.any { it.startsWith("⚡") } || CACHED.containsMatchIn(stream.releaseText)

    /** The chips a stream carries: parsed quality first, then the add-on's own. */
    fun tags(stream: StremioClient.StreamTuple): List<String> {
        val name = stream.releaseText
        val derived = listOfNotNull(
            if (isCached(stream)) "⚡ Cached" else null,
            ReleaseQuality.resolutionLabel(name).takeIf { it != "SD" },
            ReleaseQuality.sourceLabel(name),
            ReleaseQuality.hdrLabel(name),
            ReleaseQuality.codecLabel(name),
            ReleaseQuality.audioLabel(name),
            if (ReleaseQuality.isMultiAudio(name)) "MULTI" else null,
            sizeBytes(stream)?.let(::formatSize),
            seeders(stream)?.let { "👤 $it" },
        )
        return (stream.badges + derived).distinct()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20)
        else -> "%d KB".format(bytes shr 10)
    }

    /** Hidden, excluded-resolution, non-HDR and uncached rows dropped; [Filters.regex] keeps matching names. */
    fun apply(
        streams: List<StremioClient.StreamTuple>,
        sort: Sort,
        filters: Filters = Filters(),
    ): List<StremioClient.StreamTuple> {
        val regex = filters.regex?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        val kept = streams
            .filter { it.addon == null || it.addon !in filters.hiddenSources }
            .filter { (it.url != null || it.infoHash != null) }
            .filter { filters.resolutions.isEmpty() || resolution(it) in filters.resolutions }
            .filter { !filters.hdrOnly || ReleaseQuality.hasHdr(it.releaseText) }
            .filter { !filters.cachedOnly || isCached(it) }
            .let { rows -> if (regex == null) rows else rows.filter { regex.containsMatchIn(it.releaseText) } }
        return when (sort) {
            Sort.ORIGINAL -> kept
            Sort.BEST -> kept.sortedWith(bestComparator)
            Sort.QUALITY -> kept.sortedWith(
                compareByDescending<StremioClient.StreamTuple> { ReleaseQuality.sourceRank(it.releaseText) }
                    .thenByDescending { ReleaseQuality.resolutionRank(it.releaseText) }
                    .thenByDescending { ReleaseQuality.hasHdr(it.releaseText) },
            )
            Sort.SIZE -> kept.sortedByDescending { sizeBytes(it) ?: -1L }
            Sort.SEEDERS -> kept.sortedByDescending { seeders(it) ?: -1 }
            Sort.NAME -> kept.sortedBy { (it.filename ?: it.title ?: it.name ?: "").lowercase() }
        }
    }

    private val bestComparator = compareByDescending<StremioClient.StreamTuple> { isCached(it) }
        .thenByDescending { ReleaseQuality.resolutionRank(it.releaseText) }
        .thenByDescending { ReleaseQuality.sourceRank(it.releaseText) }
        .thenByDescending { ReleaseQuality.hasHdr(it.releaseText) }
        .thenByDescending { seeders(it) ?: -1 }
        .thenByDescending { sizeBytes(it) ?: -1L }

    /** What auto-play takes: the best row, or the first matching [regex] when one is set. */
    fun autoPick(streams: List<StremioClient.StreamTuple>, filters: Filters): StremioClient.StreamTuple? =
        apply(streams, Sort.BEST, filters).firstOrNull()

    fun sortOf(pref: StreamPreferences.Sort): Sort = when (pref) {
        StreamPreferences.Sort.ORIGINAL -> Sort.ORIGINAL
        StreamPreferences.Sort.BEST -> Sort.BEST
        StreamPreferences.Sort.QUALITY -> Sort.QUALITY
        StreamPreferences.Sort.SIZE -> Sort.SIZE
        StreamPreferences.Sort.SEEDERS -> Sort.SEEDERS
        StreamPreferences.Sort.NAME -> Sort.NAME
    }
}
