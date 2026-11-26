package com.carteltv.tv.data.scrape

data class ScrapeRequest(
    val imdbId: String,
    val type: String,
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val aliases: List<String> = emptyList(),
) {
    val isSeries: Boolean get() = type.equals("series", ignoreCase = true)
}

data class Release(
    val hash: String,
    val name: String,
    val seeders: Int,
    val sizeBytes: Long,
    val source: String,
)

interface ScrapeSource {
    val id: String
    fun applies(request: ScrapeRequest): Boolean
    suspend fun search(request: ScrapeRequest): List<Release>
}

internal val PUBLIC_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
)

private val HEX_HASH = Regex("^[0-9a-f]{40}$")

internal fun cleanHash(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { HEX_HASH.matches(it) && it != "0".repeat(40) }
