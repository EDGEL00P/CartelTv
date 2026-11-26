package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.youtube.YouTubeFeedFilter.Rules
import com.carteltv.tv.data.youtube.YouTubeApiClient.YouTubeShelf
import com.carteltv.tv.data.youtube.YouTubeApiClient.YouTubeVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeFeedFilterTest {

    private fun v(id: String, dur: String? = "10:00", title: String = "t", ch: String? = "c") =
        YouTubeVideo(id, title, ch, dur, null)

    @Test
    fun duration_parsing() {
        assertEquals(3723, YouTubeFeedFilter.seconds("1:02:03"))
        assertEquals(45, YouTubeFeedFilter.seconds("0:45"))
        assertEquals(null, YouTubeFeedFilter.seconds("LIVE"))
        assertEquals(null, YouTubeFeedFilter.seconds(null))
    }

    @Test
    fun short_clips_dropped_but_live_and_unknown_kept() {
        val r = Rules(minSeconds = 61)
        val out = YouTubeFeedFilter.videos(listOf(v("a", "0:30"), v("b", "1:01"), v("c", null), v("d", "LIVE")), r)
        assertEquals(listOf("b", "c", "d"), out.map { it.videoId })
    }

    @Test
    fun watched_words_and_channels_are_filtered() {
        val r = Rules(
            hideWatched = true, watchedIds = setOf("w"),
            blockedWords = listOf("reaction"), blockedChannels = listOf("spam tv"),
        )
        val out = YouTubeFeedFilter.videos(
            listOf(v("w"), v("x", title = "My REACTION video"), v("y", ch = "Spam TV"), v("z")), r,
        )
        assertEquals(listOf("z"), out.map { it.videoId })
    }

    @Test
    fun shelves_dedupe_across_and_drop_empty() {
        val out = YouTubeFeedFilter.shelves(
            listOf(YouTubeShelf("A", listOf(v("1"), v("2"))), YouTubeShelf("B", listOf(v("2"))), YouTubeShelf("C", listOf(v("3")))),
            Rules(),
        )
        assertEquals(listOf("A", "C"), out.map { it.title })
    }

    @Test
    fun list_parsing_accepts_commas_and_newlines() {
        assertEquals(listOf("a", "b", "c"), YouTubeFeedFilter.parseList(" A,b\n C ,,"))
    }
}
