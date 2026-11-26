package com.carteltv.tv.data.chapters

import com.carteltv.tv.engine.chapters.ChapterSkip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IntroDB contract: refId parsing, /segments JSON mapping, and the chapters-win merge. */
class IntroDbTest {

    @Test
    fun `movie stremio ref parses to a movie query`() {
        assertEquals(IntroSkipDatabase.SkipRef("tt0371746"), IntroSkipDatabase.parseRefId("stremio:tt0371746"))
    }

    @Test
    fun `series ref carries season and episode`() {
        assertEquals(IntroSkipDatabase.SkipRef("tt0903747", 1, 2), IntroSkipDatabase.parseRefId("series:tt0903747/1:2"))
    }

    @Test
    fun `non-imdb ids are rejected`() {
        assertNull(IntroSkipDatabase.parseRefId("stremio:custom-addon-id"))
        assertNull(IntroSkipDatabase.parseRefId("series:custom-id/1:2"))
        assertNull(IntroSkipDatabase.parseRefId("movie:12345"))
        assertNull(IntroSkipDatabase.parseRefId("live:42"))
        assertNull(IntroSkipDatabase.parseRefId("yt:dQw4w9WgXcQ"))
        assertNull(IntroSkipDatabase.parseRefId(""))
    }

    @Test
    fun `maps intro recap and outro and drops post credits`() {
        val json = """
            {"imdb_id":"tt0903747","media_type":"tv","is_movie":false,
             "intro":{"start_ms":2500,"end_ms":58000,"confidence":0.92},
             "recap":{"start_ms":1000,"end_ms":60000},
             "outro":{"start_ms":1200000,"end_ms":1260000},
             "post_credits":{"start_ms":1300000,"end_ms":1360000},
             "no_intro":false,"no_recap":false,"no_outro":false}
        """.trimIndent()
        val segs = IntroSkipDatabase.parse(json)
        assertEquals(3, segs.size)
        assertEquals(ChapterSkip.Kind.INTRO to (2500L to 58000L), segs[0].kind to (segs[0].startMs to segs[0].endMs))
        assertEquals(ChapterSkip.Kind.RECAP, segs[1].kind)
        assertEquals(ChapterSkip.Kind.CREDITS, segs[2].kind)
    }

    @Test
    fun `null and out-of-bounds segments are dropped`() {
        val json = """
            {"intro":null,
             "recap":{"start_ms":0,"end_ms":2000},
             "outro":{"start_ms":0,"end_ms":1000000}}
        """.trimIndent()
        assertTrue(IntroSkipDatabase.parse(json).isEmpty())
    }

    @Test
    fun `malformed json yields no segments`() {
        assertTrue(IntroSkipDatabase.parse("not json").isEmpty())
        assertTrue(IntroSkipDatabase.parse("404 page").isEmpty())
    }

    private fun seg(kind: ChapterSkip.Kind, start: Long, end: Long) = ChapterSkip.Segment(kind, start, end)

    @Test
    fun `chapters win per kind and community fills the gaps`() {
        val chapters = listOf(seg(ChapterSkip.Kind.INTRO, 0, 50_000))
        val community = listOf(
            seg(ChapterSkip.Kind.INTRO, 1_000, 48_000),   // must be dropped: chapters already cover intro
            seg(ChapterSkip.Kind.RECAP, 60_000, 120_000), // fill
            seg(ChapterSkip.Kind.CREDITS, 900_000, 950_000), // fill
        )
        val merged = ChapterSkip.merge(chapters, community)
        assertEquals(listOf(chapters[0], community[1], community[2]), merged)
    }

    @Test
    fun `either side alone passes through`() {
        val a = listOf(seg(ChapterSkip.Kind.INTRO, 0, 50_000))
        val b = listOf(seg(ChapterSkip.Kind.CREDITS, 900_000, 950_000))
        assertEquals(a, ChapterSkip.merge(a, emptyList()))
        assertEquals(b, ChapterSkip.merge(emptyList(), b))
    }
}
