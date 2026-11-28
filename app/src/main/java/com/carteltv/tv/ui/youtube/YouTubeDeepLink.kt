package com.carteltv.tv.ui.youtube

/**
 * Lets a YouTube item opened from Home or Continue watching land straight on playback. A watch-page URL is HTML,
 * not media: only the YouTube screen resolves it to streams, so the generic player must never be handed one.
 */
object YouTubeDeepLink {
    class Request(val videoId: String, val title: String)

    private const val REF_PREFIX = "yt:"

    @Volatile private var pending: Request? = null

    /** The video a `yt:<id>` reference names, or null when [refId] is not a YouTube reference. */
    fun requestFor(refId: String, title: String): Request? =
        refId.takeIf { it.startsWith(REF_PREFIX) }
            ?.removePrefix(REF_PREFIX)
            ?.takeIf { it.isNotBlank() }
            ?.let { Request(it, title) }

    fun post(request: Request) { pending = request }

    fun take(): Request? = pending.also { pending = null }
}
