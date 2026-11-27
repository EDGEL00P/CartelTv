package com.carteltv.tv.data.xtream

/** A full-screen tune after choosing the best suitable source already present in the provider's channel list. */
data class LiveTune(val url: String, val title: String, val refId: String)

object LiveSourceSelector {
    private val FPS = Regex("""(?i)(?<!\d)(?:100|120|60|50|30|25)\s*fps(?![a-z])""")

    /** 4K is useful only on a 4K display; 8K is not a sensible IPTV tuning target. */
    fun maxQualityForDisplayHeight(heightPx: Int?): Int = if (heightPx != null && heightPx < 2160) 4 else 5

    private fun sourceScore(name: String): Int {
        val fps = FPS.find(name)?.value?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        return ChannelDuplicates.qualityRank(name) * 200 + fps
    }

    /**
     * Chooses the best ordinary live copy before playback starts. The selected channel stays first on a tie.
     * Timeshift/custom URLs and non-live refs pass through untouched. Multi-view deliberately does not call this:
     * several high-bitrate copies at once would make the network, not the TV, the bottleneck.
     */
    fun best(
        url: String,
        title: String,
        refId: String,
        client: XtreamClient?,
        pool: Iterable<XtreamClient.LiveStream>,
        maxQualityRank: Int,
    ): LiveTune {
        if (client == null || !refId.startsWith("live:")) return LiveTune(url, title, refId)
        val id = refId.removePrefix("live:").toLongOrNull() ?: return LiveTune(url, title, refId)
        val selected = pool.firstOrNull { it.streamId == id } ?: return LiveTune(url, title, refId)
        if (client.liveUrl(id) != url) return LiveTune(url, title, refId)
        val candidates = listOf(selected) + ChannelDuplicates.copiesOf(selected, pool)
        val best = candidates
            .filter { ChannelDuplicates.qualityRank(it.name) <= maxQualityRank }
            .maxByOrNull { sourceScore(it.name) }
            ?: selected
        return LiveTune(client.liveUrl(best.streamId), best.name.ifBlank { title }, "live:${best.streamId}")
    }
}
