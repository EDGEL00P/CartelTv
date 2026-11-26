package com.carteltv.tv.data.xtream

/**
 * Picks the next quality copy to try after the playing stream failed. Each candidate is tried
 * once per [windowMs], so a channel whose copies all fail ends instead of looping. The window
 * restarts for a different channel or after it elapses, so a later press tries the best copy again.
 */
class StreamCopyFailover(private val windowMs: Long = 2 * 60_000L) {

    private var channelId: Long? = null
    private var startedAt = 0L
    private val tried = mutableSetOf<Long>()

    /** Stream ids that failed this run, shared by every channel. Bounded. */
    private val failures = LinkedHashMap<Long, Int>()

    fun failuresOf(streamId: Long): Int = failures[streamId] ?: 0

    private fun record(streamId: Long) {
        failures[streamId] = failuresOf(streamId) + 1
        if (failures.size > 500) failures.remove(failures.keys.first())
    }

    /**
     * [selected] is the channel the viewer picked, [failedId] the stream that just errored.
     * Returns the stream to switch to, or null when no untried copy is left.
     */
    fun next(
        selected: XtreamClient.LiveStream,
        failedId: Long,
        pool: Iterable<XtreamClient.LiveStream>,
        nowMs: Long,
    ): XtreamClient.LiveStream? {
        if (channelId != selected.streamId || nowMs - startedAt > windowMs) {
            channelId = selected.streamId
            startedAt = nowMs
            tried.clear()
        }
        record(failedId)
        tried += failedId
        tried += selected.streamId
        return ChannelDuplicates.copiesOf(selected, pool, ::failuresOf)
            .firstOrNull { it.streamId !in tried }
            ?.also { tried += it.streamId }
    }
}
