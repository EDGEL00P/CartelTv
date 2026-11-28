package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeChaptersDislikesTest {

    private fun root(s: String) = HttpSupport.json.parseToJsonElement(s).jsonObject

    @Test
    fun player_bar_chapters_are_parsed_and_sorted() {
        val r = root(
            """{"playerOverlays":{"playerOverlayRenderer":{"decoratedPlayerBarRenderer":{"decoratedPlayerBarRenderer":
            {"playerBar":{"multiMarkersPlayerBarRenderer":{"markersMap":[{"key":"DESCRIPTION_CHAPTERS","value":{"chapters":[
            {"chapterRenderer":{"title":{"simpleText":"String"},"timeRangeStartMillis":794000}},
            {"chapterRenderer":{"title":{"simpleText":"Vectors"},"timeRangeStartMillis":336000}}]}}]}}}}}}}""",
        )
        val c = YouTubeChapters.parse(r)
        assertEquals(listOf("Vectors", "String"), c.map { it.title })
        assertEquals(1, YouTubeChapters.indexAt(c, 800_000))
        assertEquals(-1, YouTubeChapters.indexAt(c, 1_000))
    }

    @Test
    fun panel_chapters_are_the_fallback_using_clock_labels() {
        val r = root(
            """{"engagementPanels":[{"engagementPanelSectionListRenderer":{"content":{"macroMarkersListRenderer":{"contents":[
            {"macroMarkersListItemRenderer":{"title":{"simpleText":"Hash Maps"},"timeDescription":{"simpleText":"18:25"}}},
            {"macroMarkersListItemRenderer":{"title":{"simpleText":"Intro"},"timeDescription":{"simpleText":"0:00"}}}]}}}}]}""",
        )
        assertEquals(listOf("Intro" to 0L, "Hash Maps" to 1_105_000L), YouTubeChapters.parse(r).map { it.title to it.startMs })
    }

    @Test
    fun no_chapters_gives_empty_and_bad_clock_is_null() {
        assertEquals(emptyList<YouTubeChapters.Chapter>(), YouTubeChapters.parse(root("{}")))
        assertNull(YouTubeChapters.clockMs("n/a"))
        assertEquals(3_723_000L, YouTubeChapters.clockMs("1:02:03"))
    }

    @Test
    fun rye_response_parses_and_formats() {
        val v = YouTubeDislikes.parse("""{"id":"x","likes":19512472,"dislikes":522108,"deleted":false}""")!!
        assertEquals("19.5M likes · 522.1K dislikes", v.label)
        assertNull(YouTubeDislikes.parse("""{"likes":1,"dislikes":2,"deleted":true}"""))
        assertNull(YouTubeDislikes.parse("not json"))
        assertEquals("999", YouTubeDislikes.compact(999))
        assertEquals("1K", YouTubeDislikes.compact(1000))
    }
}
