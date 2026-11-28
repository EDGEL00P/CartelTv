package com.carteltv.tv.engine.chapters

import com.carteltv.tv.engine.chapters.ChapterSkip.Chapter
import com.carteltv.tv.engine.chapters.ChapterSkip.Kind
import com.carteltv.tv.engine.playback.SeekAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekAndSkipTest {

    private val hour = 3_600_000L

    @Test fun slowPressesStepTenSecondsFromTheLastTarget() {
        val s = SeekAccumulator()
        assertEquals(70_000L, s.press(1, 0, 60_000, hour))
        assertEquals(80_000L, s.press(1, 1_000, 60_000, hour))
    }

    @Test fun rapidPressesEscalateTheStep() {
        val s = SeekAccumulator()
        var t = 0L
        var last = 0L
        repeat(10) { last = s.press(1, t, 0, hour); t += 100 }
        // 3 x 10 s, 4 x 30 s, 3 x 60 s
        assertEquals(30_000L + 120_000L + 180_000L, last)
    }

    @Test fun backwardClampsAtZeroAndForwardAtDuration() {
        val s = SeekAccumulator()
        assertEquals(0L, s.press(-1, 0, 4_000, hour))
        s.cancel()
        assertEquals(hour, s.press(1, 0, hour - 3_000, hour))
    }

    @Test fun unknownDurationOnlyBoundsBelow() {
        assertEquals(70_000L, SeekAccumulator().press(1, 0, 60_000, -1))
    }

    @Test fun commitsOnlyAfterTheKeysGoQuiet() {
        val s = SeekAccumulator()
        s.press(1, 1_000, 0, hour)
        assertFalse(s.due(1_500))
        assertTrue(s.due(1_800))
        assertEquals(10_000L, s.commit())
        assertNull(s.pendingMs)
        assertFalse(s.due(5_000))
    }

    @Test fun commitWithNoRunIsNull() {
        assertNull(SeekAccumulator().commit())
    }

    @Test fun resumeBacksOffFiveSecondsButNeverBelowZero() {
        assertEquals(55_000L, SeekAccumulator.resumeFrom(60_000))
        assertEquals(0L, SeekAccumulator.resumeFrom(8_000))
    }

    private val chapters = listOf(
        Chapter(0, "Recap"), Chapter(60_000, "Opening"), Chapter(150_000, "Part 1"),
        Chapter(1_300_000, "Credits"), Chapter(1_400_000, "Post-Credits"),
    )

    @Test fun findsRecapOpeningAndCredits() {
        val seg = ChapterSkip.segments(chapters, 1_500_000)
        assertEquals(listOf(Kind.RECAP, Kind.INTRO, Kind.CREDITS), seg.map { it.kind })
        assertEquals(60_000L..150_000L, seg[1].startMs..seg[1].endMs)
    }

    @Test fun postCreditsAreNeverOffered() {
        val seg = ChapterSkip.segments(chapters, 1_500_000)
        assertTrue(seg.none { it.startMs == 1_400_000L })
    }

    @Test fun sceneNamedChaptersDoNotMatch() {
        val seg = ChapterSkip.segments(
            listOf(Chapter(0, "The Opening Scene"), Chapter(60_000, "Alternate Ending"), Chapter(120_000, "Wrap")), 200_000,
        )
        assertTrue(seg.isEmpty())
    }

    @Test fun introCountsOnlyWithoutAnOpening() {
        val onlyIntro = ChapterSkip.segments(listOf(Chapter(0, "Intro"), Chapter(40_000, "Story")), 200_000)
        assertEquals(listOf(Kind.INTRO), onlyIntro.map { it.kind })
        val both = ChapterSkip.segments(
            listOf(Chapter(0, "Intro"), Chapter(40_000, "Opening"), Chapter(100_000, "Story")), 200_000,
        )
        assertEquals(1, both.size)
        assertEquals(40_000L, both[0].startMs)
    }

    @Test fun tooShortOrTooLongSegmentsAreDropped() {
        val seg = ChapterSkip.segments(
            listOf(Chapter(0, "Opening"), Chapter(3_000, "Recap"), Chapter(400_000, "Story")), 500_000,
        )
        assertTrue(seg.isEmpty())
    }

    @Test fun activeSegmentStopsJustBeforeItEnds() {
        val seg = ChapterSkip.segments(chapters, 1_500_000)
        assertEquals(Kind.INTRO, ChapterSkip.active(seg, 100_000)?.kind)
        assertNull(ChapterSkip.active(seg, 149_500))
        assertNull(ChapterSkip.active(seg, 500_000))
    }

    @Test fun noChaptersMeansNothingToSkip() {
        assertTrue(ChapterSkip.segments(emptyList(), hour).isEmpty())
        assertTrue(ChapterSkip.segments(listOf(Chapter(0, "Opening")), hour).isEmpty())
    }
}
