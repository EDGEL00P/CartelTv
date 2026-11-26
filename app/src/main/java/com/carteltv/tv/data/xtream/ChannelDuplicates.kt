package com.carteltv.tv.data.xtream

import java.text.Normalizer

/**
 * Finds the other quality copies of a live channel (`TSN 1 4K`, `TSN 1 FHD`, `TSN 1 HD`).
 * Identity is country tag plus the name with only quality tags removed: region, East/West,
 * +1 and channel numbers stay, so `TSN 1` and `TSN 2` are never copies. A shared EPG id is not
 * evidence. Pure, no Android types.
 */
object ChannelDuplicates {

    private val QUALITY = ChannelNameRules.QUALITY
    private val MARKS = Regex("""\p{Mn}+""")
    private val SPACES = Regex("""\s+""")

    fun identity(name: String): String {
        val country = GuideModel.countryTag(name).orEmpty()
        val folded = MARKS.replace(Normalizer.normalize(GuideModel.cleanName(name), Normalizer.Form.NFD), "")
            .replace("+", " plus ")
            .lowercase()
        return "$country|${SPACES.replace(folded, " ").trim()}"
    }

    /** Higher is better. A plain name sits between SD and HD: most providers' plain name is their HD feed. */
    fun qualityRank(name: String): Int {
        var rank = -1
        for (m in QUALITY.findAll(name)) {
            val r = when (m.groupValues[1].lowercase()) {
                "8k" -> 6
                "4k", "uhd", "2160", "2160p" -> 5
                "fhd", "1080", "1080p", "1080i" -> 4
                "hd", "720", "720p" -> 3
                else -> 1
            }
            if (r > rank) rank = r
        }
        return if (rank < 0) 2 else rank
    }

    /**
     * Other streams of the same channel in [pool], best first. [failures] maps a stream id to how
     * often it failed this run; each failure costs a tenth of a quality step, capped at nine, so a
     * broken copy sinks below working siblings without outranking a whole quality tier.
     */
    fun copiesOf(
        selected: XtreamClient.LiveStream,
        pool: Iterable<XtreamClient.LiveStream>,
        failures: (Long) -> Int = { 0 },
    ): List<XtreamClient.LiveStream> {
        val key = identity(selected.name)
        if (key.endsWith("|")) return emptyList()
        return pool
            .filter { it.streamId != selected.streamId && it.streamId != 0L && identity(it.name) == key }
            .distinctBy { it.streamId }
            .sortedByDescending { qualityRank(it.name) * 10 - failures(it.streamId).coerceIn(0, 9) }
    }
}
