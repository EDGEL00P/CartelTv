package com.carteltv.tv.data.xtream

/**
 * Token rules shared by every place that reads a provider channel name. Tags are matched with
 * "not part of a longer token" boundaries that also treat "+" as part of the token, so "Sky HD+"
 * is a different channel from "Sky HD" rather than "Sky +".
 */
internal object ChannelNameRules {

    private const val BEFORE = """(?<![\w+])"""
    private const val AFTER = """(?![\w+])"""

    private const val QUALITY_WORDS = "8k|4k|uhd|fhd|hd|sd|2160p?|1080[pi]?|720p?|576p?|480p?"

    /** Resolution and quality tags, longest spelling first within each tier. */
    val QUALITY = Regex("""(?i)$BEFORE($QUALITY_WORDS)$AFTER""")

    /** Quality, codec and feed-health tags that are never part of a channel's on-air name. */
    val NOISE = Regex(
        """(?i)$BEFORE($QUALITY_WORDS|hq|lq|hevc|h26[45]|x26[45]|hdr(?:10)?|\d{2}fps)$AFTER""",
    )

    /**
     * "RAW" is also a real name ("WWE Raw"), so it only counts as a tag when it sits next to a
     * quality or codec token ("ESPN HD RAW", "ESPN RAW 4K").
     */
    val RAW_TAG = Regex(
        """(?i)(?<=\b(?:uhd|fhd|hd|sd|4k|8k|hevc)\s)raw$AFTER|${BEFORE}raw(?=\s+(?:uhd|fhd|hd|sd|4k|8k|hevc)$AFTER)""",
    )

    /**
     * Regional feed suffix providers append to a channel name ("beIN Sports 1 ASIA - NZ").
     * Only a region word followed by a dash-separated country code counts: "Fox Sports Asia"
     * and "Sky Sports - Main Event" are real names and must survive.
     */
    val FEED_REGION_SUFFIX = Regex(
        """(?i)\s+\b(?:ASIA|LATIN|EU|EUROPE)\s*[-–—/|]\s*[A-Z]{2,3}\b\s*$""",
    )
}
