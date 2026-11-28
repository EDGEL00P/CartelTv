package com.carteltv.tv.data.xtream

/** Per-stream metadata discovered in a playlist. Null from providers that do not declare it. */
data class StreamMetadata(
    val headers: Map<String, String> = emptyMap(),
    val drm: M3uParser.DrmInfo? = null,
)
