package com.carteltv.tv.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSubsTest {

    private fun entry(id: String, title: String, published: String, link: String = "https://www.youtube.com/watch?v=$id") = """
        <entry>
          <id>yt:video:$id</id>
          <yt:videoId>$id</yt:videoId>
          <yt:channelId>UCX6OQ3DkcsbYNE6H8uQQuVA</yt:channelId>
          <title>$title</title>
          <link rel="alternate" href="$link"/>
          <author>
           <name>Some &amp; Channel</name>
           <uri>https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA</uri>
          </author>
          <published>$published</published>
          <media:group>
           <media:thumbnail url="https://i4.ytimg.com/vi/$id/hqdefault.jpg" width="480" height="360"/>
          </media:group>
        </entry>
    """.trimIndent()

    private fun feed(vararg e: String) = "<feed>\n<title>x</title>\n${e.joinToString("\n")}\n</feed>"

    @Test fun parsesIdTitleChannelAndTime() {
        val v = YouTubeSubscriptions.parseFeed(feed(entry("abc12345678", "Hello &amp; welcome", "2026-11-20T17:00:01+00:00"))).single()
        assertEquals("abc12345678", v.videoId)
        assertEquals("Hello & welcome", v.title)
        assertEquals("Some & Channel", v.channel)
        assertEquals("UCX6OQ3DkcsbYNE6H8uQQuVA", v.channelId)
        assertEquals("https://i4.ytimg.com/vi/abc12345678/hqdefault.jpg", v.thumb)
        assertTrue(v.publishedMs > 0)
    }

    @Test fun shortsAreLeftOut() {
        val xml = feed(
            entry("vid00000001", "Full video", "2026-11-20T17:00:00+00:00"),
            entry("vid00000002", "A short", "2026-11-20T18:00:00+00:00", link = "https://www.youtube.com/shorts/vid00000002"),
        )
        assertEquals(listOf("vid00000001"), YouTubeSubscriptions.parseFeed(xml).map { it.videoId })
    }

    @Test fun brokenOrEmptyFeedGivesNothing() {
        assertTrue(YouTubeSubscriptions.parseFeed("").isEmpty())
        assertTrue(YouTubeSubscriptions.parseFeed("<html>blocked</html>").isEmpty())
        assertTrue(YouTubeSubscriptions.parseFeed("<entry><title>no id</title></entry>").isEmpty())
    }

    private fun vid(id: String, at: Long) = YouTubeApiClient.YouTubeVideo(id, "t$id", null, null, null, null, at)

    @Test fun mergeIsNewestFirstAndDeduped() {
        val merged = YouTubeSubscriptions.merge(listOf(listOf(vid("a", 10), vid("b", 30)), listOf(vid("c", 20), vid("a", 10))))
        assertEquals(listOf("b", "c", "a"), merged.map { it.videoId })
    }

    @Test fun mergeIsCapped() {
        val big = (1..300).map { vid("v$it", it.toLong()) }
        assertEquals(120, YouTubeSubscriptions.merge(listOf(big)).size)
    }

    @Test fun agoLabels() {
        val now = 1_000_000_000_000L
        val m = 60_000L
        assertEquals("", YouTubeSubscriptions.ago(0L, now))
        assertEquals("just now", YouTubeSubscriptions.ago(now - 20_000L, now))
        assertEquals("5 min ago", YouTubeSubscriptions.ago(now - 5 * m, now))
        assertEquals("3 h ago", YouTubeSubscriptions.ago(now - 3 * 60 * m, now))
        assertEquals("4 d ago", YouTubeSubscriptions.ago(now - 4 * 24 * 60 * m, now))
        assertEquals("3 mo ago", YouTubeSubscriptions.ago(now - 100L * 24 * 60 * m, now))
    }

    @Test fun futureTimestampNeverGoesNegative() {
        assertEquals("just now", YouTubeSubscriptions.ago(2_000_000_000_000L, 1_000_000_000_000L))
    }

    @Test fun uploadedStampNamesTheDateAndTime() {
        assertEquals("", YouTubeSubscriptions.uploadedAt(0L))
        val label = YouTubeSubscriptions.uploadedAt(1_793_000_000_000L)
        assertTrue(label.contains("2026"))
        assertTrue(label.contains(":"))
    }

    @Test fun unescapeHandlesEntitiesInOrder() {
        assertEquals("Tom & Jerry <3 \"quotes\" it's", YouTubeSubscriptions.unescape("Tom &amp; Jerry &lt;3 &quot;quotes&quot; it&#39;s"))
        assertEquals("&lt;", YouTubeSubscriptions.unescape("&amp;lt;"))
    }
}
