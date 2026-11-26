package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeAdRendererTest {

    private fun obj(s: String) = HttpSupport.json.parseToJsonElement(s).jsonObject

    private val video = """"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Real"}}"""

    @Test
    fun ad_and_promoted_containers_are_never_parsed_as_videos() {
        listOf(
            "adSlotRenderer", "promotedSparklesWebRenderer", "promotedVideoRenderer",
            "compactPromotedVideoRenderer", "searchPyvRenderer", "inFeedAdLayoutRenderer",
        ).forEach { key ->
            assertNull(key, YouTubeApiClient.parseAnyVideoRenderer(obj("""{"$key":{"videoId":"abcdefghijk","title":{"simpleText":"Ad"}}}""")))
        }
    }

    @Test
    fun a_container_mixing_an_ad_key_with_a_video_is_still_rejected() {
        assertNull(YouTubeApiClient.parseAnyVideoRenderer(obj("""{"adSlotRenderer":{},$video}""")))
    }

    @Test
    fun normal_video_still_parses() {
        assertEquals("abcdefghijk", YouTubeApiClient.parseAnyVideoRenderer(obj("{$video}"))?.videoId)
        assertNotNull(YouTubeApiClient.parseAnyVideoRenderer(obj("{$video}")))
    }

    @Test
    fun view_count_and_upload_age_survive_parsing() {
        val raw = """"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Real"},"longBylineText":{"simpleText":"A channel"},"viewCountText":{"simpleText":"1,234,567 views"},"publishedTimeText":{"simpleText":"2 days ago"}}"""
        val v = YouTubeApiClient.parseAnyVideoRenderer(obj("{$raw}"))
        assertEquals("1,234,567 views", v?.viewsText)
        assertEquals("2 days ago", v?.publishedText)
    }
}
