package com.carteltv.tv.engine.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Another way to play the same title, tried when the one on screen dies: a direct link or a torrent hash for the debrid bridge. */
@Serializable
data class AlternateSource(
    val url: String? = null,
    val infoHash: String? = null,
    val sources: List<String> = emptyList(),
)

object AlternateSourceSelector {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AlternateSource.serializer())

    fun encode(list: List<AlternateSource>): String = json.encodeToString(serializer, list)

    fun decode(raw: String?): List<AlternateSource> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    /**
     * Walks [remaining] until [resolve] yields a playable link. Returns that link and what is left after
     * the one used, so a source that fails is never tried twice. A source that cannot be resolved is skipped.
     */
    suspend fun firstPlayable(
        remaining: List<AlternateSource>,
        resolve: suspend (AlternateSource) -> String?,
    ): Pair<String?, List<AlternateSource>> {
        var rest = remaining
        while (rest.isNotEmpty()) {
            val candidate = rest.first()
            rest = rest.drop(1)
            val link = candidate.url?.takeIf { it.isNotBlank() } ?: resolve(candidate)
            if (!link.isNullOrBlank()) return link to rest
        }
        return null to rest
    }
}
