package com.carteltv.tv.data.xtream

import com.carteltv.tv.core.suspendRunCatching

/**
 * One shared in-memory copy of the provider's VOD/movie list, so Home and Search do not each
 * download thousands of rows. Mirrors [LiveChannels] but for VOD: cache-first within [TTL_MS],
 * then a refresh. Falls back to the previous snapshot on failure.
 */
object VodCatalogCache {
    private const val TTL_MS = 10 * 60_000L

    @Volatile private var streams: List<XtreamClient.VodStream> = emptyList()
    @Volatile private var streamsAt = 0L
    @Volatile private var owner: String? = null

    private fun claim(client: XtreamClient) {
        val id = "${client.baseUrl}|${client.username}"
        if (owner != id) { owner = id; streams = emptyList(); streamsAt = 0L }
    }

    suspend fun all(client: XtreamClient, force: Boolean = false): List<XtreamClient.VodStream> {
        claim(client)
        val now = System.currentTimeMillis()
        if (!force && streams.isNotEmpty() && now - streamsAt < TTL_MS) return streams
        val fresh = suspendRunCatching { client.vodStreams(null) }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) {
            streams = fresh
            streamsAt = System.currentTimeMillis()
        }
        return if (streams.isNotEmpty()) streams else fresh
    }

    /** Forgets the cached list so the next screen reloads it. */
    fun invalidate() { streamsAt = 0L }

    /** The already-loaded list, never a network call. Empty until a screen has loaded it. */
    fun cached(): List<XtreamClient.VodStream> = streams
}