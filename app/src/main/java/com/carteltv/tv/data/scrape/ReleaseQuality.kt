package com.carteltv.tv.data.scrape

/** What a release name says about picture quality, for ranking and for dropping unwatchable copies. */
internal object ReleaseQuality {
    private val RESOLUTIONS = listOf(
        "2160p" to Regex("""(?i)(?<![a-z0-9])(2160p|4k|uhd)(?![a-z0-9])"""),
        "1080p" to Regex("""(?i)(?<![a-z0-9])1080[pi](?![a-z0-9])"""),
        "720p" to Regex("""(?i)(?<![a-z0-9])720p(?![a-z0-9])"""),
    )

    /** Higher is better; the label is what the row shows. */
    private val SOURCES = listOf(
        Triple("Remux", 6, Regex("""(?i)(?<![a-z0-9])remux(?![a-z0-9])""")),
        Triple("BluRay", 5, Regex("""(?i)(?<![a-z0-9])(blu-?ray|bdrip|brrip)(?![a-z0-9])""")),
        Triple("WEB-DL", 4, Regex("""(?i)(?<![a-z0-9])web[-. ]?dl(?![a-z0-9])""")),
        Triple("WEBRip", 3, Regex("""(?i)(?<![a-z0-9])(web[-. ]?rip|web)(?![a-z0-9])""")),
        Triple("HDTV", 2, Regex("""(?i)(?<![a-z0-9])(hdtv|pdtv)(?![a-z0-9])""")),
        Triple("DVDRip", 1, Regex("""(?i)(?<![a-z0-9])(dvdrip|dvd)(?![a-z0-9])""")),
    )

    private val HDR = Regex("""(?i)(?<![a-z0-9])(hdr10\+?|hdr|dolby[ .-]?vision|dv)(?![a-z0-9])""")

    /** Cam, telesync and screener copies, samples and bonus-content uploads: a title match that is not the film. Runs on text with separators turned into spaces. */
    private val JUNK = Regex(
        """(?<![a-z0-9])(cam|hdcam|camrip|ts|hdts|telesync|tc|hdtc|telecine|scr|screener|dvdscr|r5|workprint|sample|trailer|featurette|behind the scenes|making of|deleted scenes|extras|soundtrack|ost|bonus)(?![a-z0-9])""",
    )

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    private fun spaced(text: String) = text.lowercase().replace(NON_ALNUM, " ").trim()

    fun resolutionRank(name: String): Int {
        val i = RESOLUTIONS.indexOfFirst { it.second.containsMatchIn(name) }
        return if (i < 0) 0 else RESOLUTIONS.size - i
    }

    fun resolutionLabel(name: String): String = RESOLUTIONS.firstOrNull { it.second.containsMatchIn(name) }?.first ?: "SD"

    private fun source(name: String) = SOURCES.firstOrNull { it.third.containsMatchIn(name) }

    fun sourceRank(name: String): Int = source(name)?.second ?: 0

    fun sourceLabel(name: String): String? = source(name)?.first

    fun hasHdr(name: String): Boolean = HDR.containsMatchIn(name)

    private val DOLBY_VISION = Regex("""(?i)(?<![a-z0-9])(dolby[ .-]?vision|dv)(?![a-z0-9])""")
    private val HDR_ONLY = Regex("""(?i)(?<![a-z0-9])hdr10\+?|(?<![a-z0-9])hdr(?![a-z0-9])""")

    fun hdrLabel(name: String): String? = listOfNotNull(
        if (DOLBY_VISION.containsMatchIn(name)) "DV" else null,
        if (HDR_ONLY.containsMatchIn(name)) "HDR" else null,
    ).joinToString(" ").ifEmpty { null }

    private val CODECS = listOf(
        "AV1" to Regex("""(?i)(?<![a-z0-9])av1(?![a-z0-9])"""),
        "HEVC" to Regex("""(?i)(?<![a-z0-9])(x265|h[ .]?265|hevc)(?![a-z0-9])"""),
        "x264" to Regex("""(?i)(?<![a-z0-9])(x264|h[ .]?264|avc)(?![a-z0-9])"""),
    )

    fun codecLabel(name: String): String? = CODECS.firstOrNull { it.second.containsMatchIn(name) }?.first

    private val AUDIO = listOf(
        "Atmos" to Regex("""(?i)(?<![a-z0-9])atmos(?![a-z0-9])"""),
        "TrueHD" to Regex("""(?i)(?<![a-z0-9])truehd(?![a-z0-9])"""),
        "DTS-HD" to Regex("""(?i)(?<![a-z0-9])dts[ .-]?(hd|x)(?![a-z0-9])"""),
        // Channel counts are glued on in real names ("DDP5.1", "AAC2.0"), so only a following letter ends the word.
        "DTS" to Regex("""(?i)(?<![a-z0-9])dts(?![a-z])"""),
        "DD+" to Regex("""(?i)(?<![a-z0-9])(ddp|dd\+|e-?ac-?3)(?![a-z])"""),
        "DD" to Regex("""(?i)(?<![a-z0-9])(dd|ac-?3)(?![a-z])"""),
        "AAC" to Regex("""(?i)(?<![a-z0-9])aac(?![a-z])"""),
    )

    fun audioLabel(name: String): String? = AUDIO.firstOrNull { it.second.containsMatchIn(name) }?.first

    private val MULTI_AUDIO = Regex("""(?i)(?<![a-z0-9])(multi|dual)(?![a-z0-9])""")

    fun isMultiAudio(name: String): Boolean = MULTI_AUDIO.containsMatchIn(name)

    private val GROUP = Regex("""-([A-Za-z0-9]{2,12})(?:\.[a-z0-9]{2,4})?$""")

    /** The release group, the "-GRP" at the end of a scene-style name. */
    fun group(name: String): String? = GROUP.find(name.trim())?.groupValues?.get(1)
        ?.takeUnless { it.lowercase() in NOT_A_GROUP || RESOLUTIONS.any { r -> r.second.matches(it) } }

    /** Tails of hyphenated format words ("WEB-DL", "Blu-ray", "DTS-HD") that the group pattern would otherwise pick up. */
    private val NOT_A_GROUP = setOf("dl", "ray", "hd", "rip", "ma", "x", "hdr", "dv")

    /** The requested title is cut out first, so a film called "The Sample" is not junk but a "SAMPLE" tag after it is. */
    fun isJunk(name: String, requestedTitle: String): Boolean {
        val title = spaced(requestedTitle)
        val rest = " " + spaced(name).let { if (title.isEmpty()) it else it.replaceFirst(title, " ") } + " "
        return JUNK.containsMatchIn(rest)
    }
}
