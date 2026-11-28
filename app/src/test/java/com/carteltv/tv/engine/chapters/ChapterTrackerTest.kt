package com.carteltv.tv.engine.chapters

import com.carteltv.tv.engine.chapters.ChapterSkip.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterTrackerTest {

    private val chapters = listOf(
        Chapter(0, "Intro"),
        Chapter(30_000, "Part One"),
        Chapter(90_000, "Credits"),
    )
    private val duration = 120_000L

    private fun ChapterTracker.tick(pos: Long, playing: Boolean = true, delta: Long = 250): Int? =
        onTick(pos, playing, delta)

    @Test fun completesAtNinetyPercentOfTheSpan() {
        val t = ChapterTracker(chapters, duration)
        var r: Int? = null
        repeat(107) { r = t.tick(10_000) } // 107 * 250 = 26.75 s of 30 s span
        assertNull(r)
        assertEquals(0, t.tick(10_000)) // 108 * 250 = 27 s = 90% -> completes now
    }

    @Test fun completionFiresOnlyOncePerChapterPerSession() {
        val t = ChapterTracker(chapters, duration)
        var fired = false
        repeat(120) { fired = fired || (t.tick(5_000) == 0) }
        assertEquals(true, fired)
        assertNull(t.tick(5_000))
        assertNull(t.tick(5_000))
    }

    @Test fun pausedTicksCreditNothing() {
        val t = ChapterTracker(chapters, duration)
        repeat(200) { t.tick(10_000, playing = false, delta = 250) }
        assertNull(t.tick(10_000, playing = false, delta = 250))
        var r: Int? = null
        repeat(120) { t.tick(10_000)?.let { r = it } }
        assertEquals(0, r)
    }

    @Test fun backwardSeekKeepsCreditSoTheTailFinishesTheChapter() {
        val t = ChapterTracker(chapters, duration)
        repeat(100) { t.tick(10_000) } // 25 s credit
        t.tick(29_000) // still inside the same chapter; credit carries
        var r: Int? = null
        repeat(6) { r = t.tick(25_000) } // +1.5 s -> 26.75 s, still short
        assertNull(r)
        r = t.tick(29_500) // +0.25 -> 27 s >= 27 s
        assertEquals(0, r)
    }

    @Test fun forwardScrubThroughDoesNotCompleteTheChapter() {
        val t = ChapterTracker(chapters, duration)
        assertNull(t.tick(0))
        assertNull(t.tick(31_000)) // left chapter 0 forward; its partial credit is cleared
        // Now inside "Part One": only its own playtime counts.
        var r: Int? = null
        repeat(10) { r = t.tick(45_000) }
        assertNull(r)
    }

    @Test fun eachChapterCompletesOnItsOwnWatch() {
        val t = ChapterTracker(chapters, duration)
        var r0: Int? = null
        repeat(120) { t.tick(10_000)?.let { r0 = it } }
        assertEquals(0, r0)
        var r1: Int? = null
        repeat(240) { t.tick(60_000)?.let { r1 = it } } // 60 s span -> completes at 54 s = 217 ticks
        assertEquals(1, r1)
    }

    @Test fun partialCreditRestoresAcrossASessionBoundary() {
        val t = ChapterTracker(chapters, duration)
        t.restoreCredit(0, 20_000)
        var r: Int? = null
        repeat(27) { r = t.tick(10_000) } // 27 * 250 = 6.75 s; 20 s + 6.75 s < 27 s
        assertNull(r)
        assertEquals(0, t.tick(10_000)) // 7 s -> 27 s total
    }

    @Test fun indexAtFindsTheSpanAndChapterlessFilesNoOp() {
        val t = ChapterTracker(chapters, duration)
        assertEquals(0, t.indexAt(0))
        assertEquals(0, t.indexAt(29_999))
        assertEquals(1, t.indexAt(30_000))
        assertEquals(2, t.indexAt(119_999))
        assertEquals(-1, t.indexAt(120_000))

        val none = ChapterTracker(emptyList(), duration)
        assertNull(none.onTick(1_000, true, 250))
        assertEquals(-1, none.indexAt(1_000))
    }

    @Test fun unsortedChaptersAreOrderedByStart() {
        val t = ChapterTracker(listOf(Chapter(90_000, "C"), Chapter(0, "A"), Chapter(30_000, "B")), duration)
        assertEquals(0, t.indexAt(15_000))
        assertEquals(2, t.indexAt(100_000))
    }

    @Test fun singleChapterFileCompletesAtNinetyPercent() {
        val t = ChapterTracker(listOf(Chapter(0, null)), 10_000L)
        var r: Int? = null
        repeat(8) { r = t.tick(5_000, delta = 1_000) } // 8 s of 10 s
        assertNull(r)
        assertEquals(0, t.tick(5_000, delta = 1_000)) // 9 s = 90%
        assertFalse(t.onTick(5_000, true, 500) != null)
    }
}
