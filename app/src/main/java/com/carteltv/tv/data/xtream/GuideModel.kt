package com.carteltv.tv.data.xtream

import java.util.Locale

/** Pure guide maths: EPG entries to grid cells, plus channel-name tidying. No Android types. */
object GuideModel {

    const val SLOT_MS = 30 * 60_000L
    const val MIN_CELL_MIN = 4f

    enum class Phase { PAST, LIVE, FUTURE }

    data class Slot(val title: String, val description: String?, val startMs: Long, val stopMs: Long)

    data class Cell(val slot: Slot, val offsetMin: Float, val widthMin: Float, val phase: Phase)

    /** Parses provider entries into ordered, non-overlapping slots. A missing end is taken from the next start. */
    fun slots(entries: List<XtreamClient.EpgEntry>): List<Slot> {
        val raw = entries.mapNotNull { e ->
            val start = e.startTs?.toLongOrNull()?.let { it * 1000L } ?: return@mapNotNull null
            val stop = e.stopTs?.toLongOrNull()?.let { it * 1000L } ?: 0L
            Triple(e, start, stop)
        }.sortedBy { it.second }.distinctBy { it.second }
        return raw.mapIndexedNotNull { i, (e, start, stop) ->
            val end = when {
                stop > start -> stop
                i + 1 < raw.size -> raw[i + 1].second
                else -> start + SLOT_MS * 2
            }
            val clipped = if (i + 1 < raw.size) minOf(end, raw[i + 1].second) else end
            if (clipped <= start) null
            else Slot(cleanTitle(e.title?.trim().orEmpty()).ifBlank { "No title" }, e.description, start, clipped)
        }
    }

    fun phase(slot: Slot, nowMs: Long): Phase = when {
        slot.stopMs <= nowMs -> Phase.PAST
        slot.startMs > nowMs -> Phase.FUTURE
        else -> Phase.LIVE
    }

    /** The grid window opens an hour before now, on a half-hour line. */
    fun windowStart(nowMs: Long): Long = (nowMs - 60 * 60_000L) / SLOT_MS * SLOT_MS

    /** Cells clipped to [windowStartMs, windowEndMs]; programmes wholly outside it are dropped. */
    fun cells(slots: List<Slot>, windowStartMs: Long, windowEndMs: Long, nowMs: Long): List<Cell> =
        slots.mapNotNull { s ->
            if (s.stopMs <= windowStartMs || s.startMs >= windowEndMs) return@mapNotNull null
            val from = maxOf(s.startMs, windowStartMs)
            val to = minOf(s.stopMs, windowEndMs)
            val width = ((to - from) / 60_000f).coerceAtLeast(MIN_CELL_MIN)
            Cell(s, (from - windowStartMs) / 60_000f, width, phase(s, nowMs))
        }

    fun progress(slot: Slot, nowMs: Long): Float =
        ((nowMs - slot.startMs).toFloat() / (slot.stopMs - slot.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)

    fun minutesLeft(slot: Slot, nowMs: Long): Int = ((slot.stopMs - nowMs) / 60_000L).toInt().coerceAtLeast(0)

    /** "Ending now" inside the last five minutes, else a plain "N min left". */
    fun leftLabel(slot: Slot, nowMs: Long): String {
        val m = minutesLeft(slot, nowMs)
        return if (m <= 5) "Ending now" else if (m < 60) "$m min left" else "${m / 60} h ${m % 60} min left"
    }

    private val QUALITY = listOf(
        "8K" to Regex("""(?i)(?<![\w+])8k(?![\w+])"""),
        "4K" to Regex("""(?i)(?<![\w+])(4k|uhd|2160p?)(?![\w+])"""),
        "FHD" to Regex("""(?i)(?<![\w+])(fhd|1080p?)(?![\w+])"""),
        "HD" to Regex("""(?i)(?<![\w+])(hd|720p?)(?![\w+])"""),
        "SD" to Regex("""(?i)(?<![\w+])(sd|480p?|576p?)(?![\w+])"""),
    )

    fun quality(name: String): String? = QUALITY.firstOrNull { it.second.containsMatchIn(name) }?.first

    // Group 1 is the country tag in any of its spellings: "[UK]", "|UK|", "(UK)", "UK:", "UK |", "UK >", "UK - ".
    // Leading symbols and emoji (stars, arrows, bullets) are skipped; a bare "-" only counts before a space so
    // that "BBC-One" keeps its name.
    private val PREFIX = Regex(
        """^[\s\p{So}\p{Sk}\p{Sm}•·]*(\[[A-Za-z]{2,4}]|\|[A-Za-z]{2,4}\||\([A-Za-z]{2,4}\)|[A-Za-z]{2,4}\s*(?:[:|➔→»>]+|-+(?=\s)))\s*""",
    )
    private val NOISE = ChannelNameRules.NOISE
    private val BRACKET_TAG = Regex(
        """(?i)[\[(]\s*(?:8k|4k|uhd|fhd|hd|sd|hq|lq|hevc|raw|hdr|vip|backup|multi|\d{2}fps|1080[pi]?|720p?)\s*[\])]""",
    )
    // Event-slot suffixes: "[EVENT ONLY]" and "[Team A vs. Team B (2026-11-23 20:10:00)]" (the event shows as the programme).
    private val SLOT_TAG = Regex(
        """(?i)\s*\[\s*(?:event only|[^\[\]]*\(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\))\s*]\s*$""",
    )
    private val SUPERSCRIPT_TAG = Regex("""[ᴴᴰᶠᵁᴿᴬᵂᴸᵠᴷ⁴⁸ᴴᴱᵛᶜ]{2,}""")
    private val SYMBOLS = Regex("""[\p{So}\p{Sk}•·★☆]+""")
    private val SPACES = Regex("""\s{2,}""")

    /**
     * Reads like the channel's on-air name: drops country prefixes ("US|", "|UK|", "[CA]"), quality and codec
     * tags (plain, bracketed or superscript), stray symbols and emoji, and tidies the separators left behind.
     */
    fun cleanName(name: String): String {
        var s = SLOT_TAG.replace(name, "")
        s = ChannelNameRules.FEED_REGION_SUFFIX.replace(s, "")
        repeat(2) { s = PREFIX.replace(s, "") }
        s = ChannelNameRules.RAW_TAG.replace(s, " ")
        s = SUPERSCRIPT_TAG.replace(BRACKET_TAG.replace(s, " "), " ")
        s = SYMBOLS.replace(NOISE.replace(s, " "), " ")
        s = SPACES.replace(s, " ").trim(' ', '-', '|', ':', '*', '/', '>', '»')
        return s.ifBlank { name.trim() }
    }

    private val TITLE_LEAD = Regex(
        """(?i)^\s*(?:[\[(]\s*(?:new|live|repeat|rerun|premiere|encore)\s*[\])]|(?:new|live|repeat|rerun|premiere|encore)(?:\s+episode)?\s*[:|\-–]+)\s*""",
    )
    private val TITLE_TRAIL = Regex(
        """(?i)\s*[\[(]\s*(?:new|live|r|repeat|rerun|cc|hd|sap|stereo|ad|subtitled)\s*[\])]\s*$""",
    )

    /** A programme title without the "NEW:" / "LIVE:" prefixes and "(R)" / "[CC]" style suffixes guides add. */
    fun cleanTitle(title: String): String {
        var s = title
        repeat(2) { s = TITLE_TRAIL.replace(TITLE_LEAD.replace(s, ""), "") }
        return SPACES.replace(s, " ").trim().ifBlank { title.trim() }
    }

    /**
     * Name as the viewer should see it in the guide: cleaned, then shouting turned into a readable
     * channel name. [cleanName] stays byte-for-byte for matching (EPG ids, logos, stream copies);
     * only the rendering path uses this.
     */
    fun displayName(name: String): String = displayCase(cleanName(name))

    // Tokens that keep their caps in a channel name, plus a few brands with a non-obvious spelling.
    private val CAPS = setOf(
        "ESPN", "ESPN2", "CNN", "BBC", "CBS", "NBC", "ABC", "HBO", "MTV", "TNT", "USA", "FX", "AMC", "PBS", "CW",
        "TV", "NFL", "NBA", "NHL", "MLB", "MLS", "UFC", "WWE", "F1", "ATP", "WTA", "PGA", "NCAA", "IPL", "EPL",
        "TSN", "CTV", "CBC", "RTE", "VIP", "PPV", "HD", "SD", "FHD", "UHD", "FS1", "FS2", "BTN", "MLS",
        "US", "UK", "CA", "AU", "IE", "NZ", "ES", "FR", "DE", "IT", "BR", "MX",
    )
    private val BRANDS = mapOf("bein" to "beIN")
    private val LITTLE_WORDS = setOf("of", "and", "the", "for", "to", "in", "on", "at")

    private fun displayCase(clean: String): String {
        if (clean.isBlank()) return clean
        return clean.split(' ').mapIndexed { idx, word ->
            if (word.isEmpty() || word.startsWith('[') || word.startsWith('(')) return@mapIndexed word
            val lower = word.lowercase(Locale.ROOT)
            BRANDS[lower]?.let { return@mapIndexed it }
            val core = word.trimEnd { !it.isLetterOrDigit() }
            if (core.isNotEmpty() && core.lowercase(Locale.ROOT).uppercase(Locale.ROOT) in CAPS) return@mapIndexed word
            if (word.any { it.isLowerCase() } || !word.any { it.isLetter() }) return@mapIndexed word
            if (idx > 0 && lower in LITTLE_WORDS) return@mapIndexed lower
            lower[0].uppercaseChar() + lower.substring(1)
        }.joinToString(" ")
    }

    /** Country tag from a "US:" / "[UK]" / "|CA|" prefix, if the name carries one. */
    fun countryTag(name: String): String? =
        PREFIX.find(name)?.groupValues?.get(1)?.trim('[', ']', '(', ')', ' ', ':', '|', '-', '➔', '→', '»', '>')
            ?.uppercase()?.takeIf { it.length in 2..4 }
}
