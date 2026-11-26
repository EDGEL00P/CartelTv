package com.carteltv.tv.engine.chapters

import com.carteltv.tv.engine.chapters.ChapterSkip.Chapter

/**
 * Pure chapter-completion logic fed by the player tick loop. A chapter completes when the
 * accumulated playtime inside it reaches [COMPLETION_FRACTION] of its span. Only playing time
 * counts — the caller passes the speed-corrected delta and passes 0 while paused or buffering,
 * so seeks never credit a chapter. Accumulated credit survives backward seeks within the
 * chapter (rewatching the tail finishes it) but resets when the user leaves the chapter
 * forward, so a scrub-through past the threshold does not count as a watch.
 */
class ChapterTracker(chapters: List<Chapter>, durationMs: Long) {

    data class Span(val index: Int, val startMs: Long, val endMs: Long) {
        fun contains(positionMs: Long) = positionMs >= startMs && positionMs < endMs
    }

    private val spans: List<Span>
    private val credit = LongArray(chapters.size)

    init {
        val sorted = chapters.sortedBy { it.startMs }.filter { it.startMs >= 0 }
        spans = sorted.mapIndexed { i, chapter ->
            val end = if (i + 1 < sorted.size) sorted[i + 1].startMs else durationMs
            Span(i, chapter.startMs, if (end > chapter.startMs) end else chapter.startMs + 1)
        }
    }

    private var lastIndex = -1

    /** The chapter containing [positionMs], or null when the file has no chapters there. */
    fun indexAt(positionMs: Long): Int = spanAt(positionMs)?.index ?: -1

    /**
     * Feeds one tick. Returns the index of a chapter that just crossed the completion
     * threshold, or null. A completion fires at most once per chapter per session.
     */
    fun onTick(positionMs: Long, playing: Boolean, playtimeDeltaMs: Long): Int? {
        val span = spanAt(positionMs) ?: return null
        if (span.index != lastIndex) {
            // Left a chapter forward: partial credit is gone (scrub-through is not a watch).
            // Backward re-entry keeps credit so the final stretch completes the chapter.
            if (span.index > lastIndex && lastIndex >= 0) credit[lastIndex] = 0L
            lastIndex = span.index
        }
        if (!playing || playtimeDeltaMs <= 0) return null
        if (completed(span.index)) return null
        credit[span.index] += playtimeDeltaMs
        return if (completed(span.index)) span.index else null
    }

    /** Persisted partial credit from a previous session, added before the next tick. */
    fun restoreCredit(index: Int, partialMs: Long) {
        if (index in credit.indices && partialMs > 0) credit[index] += partialMs
    }

    private fun completed(index: Int): Boolean {
        val span = spans.getOrNull(index) ?: return false
        return credit[index] >= (span.endMs - span.startMs) * COMPLETION_FRACTION
    }

    private fun spanAt(positionMs: Long): Span? = spans.firstOrNull { it.contains(positionMs) }

    companion object {
        const val COMPLETION_FRACTION = 0.9
    }
}
