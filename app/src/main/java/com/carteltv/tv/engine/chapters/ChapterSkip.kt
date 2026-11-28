package com.carteltv.tv.engine.chapters

/**
 * Finds the intro, recap and credits in a file's chapters. Only titles that are essentially the segment label
 * (with an optional number) match, so scene chapters such as "The Opening Scene" or "Alternate Ending" do not;
 * post-, mid- and after-credits scenes are never offered for skipping. "Intro" counts as an opening only when the
 * file has no chapter named Opening.
 */
object ChapterSkip {

    enum class Kind(val label: String) { INTRO("Skip intro"), RECAP("Skip recap"), CREDITS("Skip credits") }

    data class Chapter(val startMs: Long, val title: String?)

    data class Segment(val kind: Kind, val startMs: Long, val endMs: Long) {
        fun contains(positionMs: Long) = positionMs >= startMs && positionMs < endMs - END_MARGIN_MS
    }

    private enum class Label { OPENING, INTRO, ENDING, RECAP, POST_CREDITS }

    private val OPENING = Regex("^\\s*(op(ening)?)(\\s*(credits|theme))?\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val ENDING = Regex("^\\s*(ed|ending)(\\s*(credits|theme))?\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val CREDITS = Regex("^\\s*(end|closing)?\\s*credits\\s*$", RegexOption.IGNORE_CASE)
    private val RECAP = Regex("^\\s*(recap|previously(\\s+on.*)?)\\s*$", RegexOption.IGNORE_CASE)
    private val INTRO = Regex("^\\s*intro\\s*$", RegexOption.IGNORE_CASE)
    private val POST = Regex("(post|mid|after)[\\s-]credits", RegexOption.IGNORE_CASE)

    private const val MIN_MS = 5_000L
    private const val MAX_MS = 240_000L
    private const val END_MARGIN_MS = 1_000L

    private fun classify(title: String?): Label? = when {
        title.isNullOrBlank() -> null
        POST.containsMatchIn(title) -> Label.POST_CREDITS
        OPENING.matches(title) -> Label.OPENING
        ENDING.matches(title) || CREDITS.matches(title) -> Label.ENDING
        RECAP.matches(title) -> Label.RECAP
        INTRO.matches(title) -> Label.INTRO
        else -> null
    }

    /** Skippable segments of a file whose chapters start at [chapters] and which lasts [durationMs]. */
    fun segments(chapters: List<Chapter>, durationMs: Long): List<Segment> {
        if (chapters.size < 2 || durationMs <= 0L) return emptyList()
        val sorted = chapters.sortedBy { it.startMs }
        val labels = sorted.map { classify(it.title) }
        val hasOpening = labels.any { it == Label.OPENING }
        return sorted.mapIndexedNotNull { i, chapter ->
            val label = labels[i] ?: return@mapIndexedNotNull null
            val kind = when (label) {
                Label.OPENING -> Kind.INTRO
                Label.INTRO -> if (hasOpening) return@mapIndexedNotNull null else Kind.INTRO
                Label.ENDING -> Kind.CREDITS
                Label.RECAP -> Kind.RECAP
                Label.POST_CREDITS -> return@mapIndexedNotNull null
            }
            val end = if (i + 1 < sorted.size) sorted[i + 1].startMs else durationMs
            val length = end - chapter.startMs
            if (length < MIN_MS || length > MAX_MS) null else Segment(kind, chapter.startMs, end)
        }
    }

    /** The segment [positionMs] is inside, if any. */
    fun active(segments: List<Segment>, positionMs: Long): Segment? = segments.firstOrNull { it.contains(positionMs) }

    /**
     * Fills kinds missing from [fromChapters] with segments from [fromCommunity] (IntroDB). File
     * chapters win per kind: they are exact for this rip, while community timestamps are keyed by
     * title and can drift across releases of the same film.
     */
    fun merge(fromChapters: List<Segment>, fromCommunity: List<Segment>): List<Segment> {
        if (fromCommunity.isEmpty()) return fromChapters
        if (fromChapters.isEmpty()) return fromCommunity
        val kinds = fromChapters.map { it.kind }.toSet()
        return fromChapters + fromCommunity.filter { it.kind !in kinds }
    }
}
