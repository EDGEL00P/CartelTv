package com.carteltv.tv.ui.home

/** A stored title split into what a person reads: the film or show, and an episode's place when the title carries it. */
internal data class TitleParts(
    val name: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** "S1, E2": an episode's place in the terms streaming apps use. Null for a film, or when the numbers are unknown. */
    val episodeLabel: String?
        get() = if (season != null && episode != null) "S$season, E$episode" else null
}

/** The play hint every resumable card shares, so the up-next row and the hero banner never word it differently. */
internal const val PLAY_HINT = "Press OK to play."

/** The episode tail the player titles carry: "Show S1 E2", "Show S1E2", and an episode's own name after a colon. */
private val EPISODE_TAIL = Regex("""\s+[sS](\d{1,2})\s*[eE](\d{1,4})(?:\s*[:\-\u2013]\s*.*)?$""")

/**
 * A language tag some feeds set in front of a title ("EN My Name Is Earl"). Only tags that are never the first
 * word of an English title are listed: "IT Crowd", "NO TIME TO DIE", "US Marshals" and "EL CAMINO" keep theirs.
 */
private val LANGUAGE_LEAD = Regex("""^(?:AR|BN|CS|EN|ES|FR|HI|HU|JA|KO|NL|PL|PT|RO|RU|SV|TA|TE|TH|TR|UA|UR|VI|ZH)\s+""")

/**
 * Reads a title the way a person does. The player stores an episode as one line, so the show's name comes
 * out as the title and the episode's place is kept apart for the line beneath. Anything else is left as it is.
 */
internal fun splitTitle(raw: String): TitleParts {
    val tail = EPISODE_TAIL.find(raw)
    val head = if (tail != null) raw.substring(0, tail.range.first) else raw
    val name = LANGUAGE_LEAD.replace(head, "").trim().ifBlank { raw.trim() }
    return TitleParts(
        name = name,
        season = tail?.groupValues?.get(1)?.toIntOrNull(),
        episode = tail?.groupValues?.get(2)?.toIntOrNull(),
    )
}

/** What the card for something started in this app says: what OK will do, where the episode sits, and how long is left. */
internal fun resumeLine(
    parts: TitleParts,
    positionMs: Long,
    durationMs: Long,
): String {
    val left = if (durationMs <= 0) null else "${((durationMs - positionMs) / 60_000L).coerceAtLeast(1L)}m left"
    return listOfNotNull("Continue", parts.episodeLabel, left).joinToString(" \u00b7 ")
}

/** What the banner says under a title being resumed: the episode's place, then what is left and how far in it is. */
internal fun resumeDescription(
    parts: TitleParts,
    positionMs: Long,
    durationMs: Long,
): String {
    val progress =
        if (durationMs > 0) {
            val left = ((durationMs - positionMs) / 60_000L).coerceAtLeast(1L)
            val pct = (positionMs * 100 / durationMs).toInt()
            "${left}m left \u00b7 $pct% watched"
        } else {
            PLAY_HINT
        }
    return listOfNotNull(parts.episodeLabel, progress).joinToString(" \u00b7 ")
}

/** What the card for another app's Watch Next programme says: the row the system filed it under. */
internal fun watchNextLine(watchNextType: Int): String =
    when (watchNextType) {
        // android.provider.TvContract.WatchNextPrograms: WATCH_NEXT_TYPE_CONTINUE, NEXT, NEW, then WATCHLIST.
        0 -> "Continue"

        1 -> "Next episode"

        2 -> "New"

        3 -> "Watchlist"

        // The provider leaves the type out when it does not know; the repository also passes it as Int.MAX_VALUE.
        else -> "Up next"
    }
