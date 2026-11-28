package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.xtream.XtreamClient.LiveStream
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSourceSelectorTest {
    private val client = XtreamClient("https://provider.example", "u", "p")

    private fun stream(id: Long, name: String) = LiveStream(streamId = id, name = name)
    private val pool = listOf(
        stream(1, "US| ESPN HD 30fps"),
        stream(2, "US| ESPN FHD 60fps"),
        stream(3, "US| ESPN 4K 30fps"),
        stream(4, "US| ESPN SD"),
        stream(5, "US| ESPN 8K"),
    )

    @Test
    fun fullHdDisplayChoosesBestSportsCopyWithoutSelecting4kOr8k() {
        val tune = LiveSourceSelector.best(
            client.liveUrl(1), "US| ESPN HD 30fps", "live:1", client, pool,
            LiveSourceSelector.maxQualityForDisplayHeight(1080),
        )

        assertEquals(client.liveUrl(2), tune.url)
        assertEquals("live:2", tune.refId)
        assertEquals("US| ESPN FHD 60fps", tune.title)
    }

    @Test
    fun fourKDisplayChoosesHighestSuitableCopy() {
        val tune = LiveSourceSelector.best(
            client.liveUrl(1), "US| ESPN HD 30fps", "live:1", client, pool,
            LiveSourceSelector.maxQualityForDisplayHeight(2160),
        )

        assertEquals(client.liveUrl(3), tune.url)
        assertEquals("live:3", tune.refId)
    }

    @Test
    fun equalQualityPrefersHigherFrameRateAndKeepsSelectedOnExactTie() {
        val sources = listOf(
            stream(10, "US| ESPN FHD 30fps"),
            stream(11, "US| ESPN FHD 60fps"),
            stream(12, "US| ESPN FHD 60fps"),
        )
        val faster = LiveSourceSelector.best(client.liveUrl(10), "ESPN", "live:10", client, sources, 4)
        assertEquals("live:11", faster.refId)

        val tied = LiveSourceSelector.best(client.liveUrl(11), "ESPN", "live:11", client, sources, 4)
        assertEquals("live:11", tied.refId)
    }

    @Test
    fun timeshiftAndNonLiveTunesPassThrough() {
        val timeshiftUrl = client.timeshiftUrl(1, 1_000L, 2_000L)
        val timeshift = LiveSourceSelector.best(timeshiftUrl, "ESPN replay", "live:1", client, pool, 4)
        assertEquals(LiveTune(timeshiftUrl, "ESPN replay", "live:1"), timeshift)

        val vod = LiveSourceSelector.best("https://media.example/movie.m3u8", "Movie", "movie:9", client, pool, 4)
        assertEquals(LiveTune("https://media.example/movie.m3u8", "Movie", "movie:9"), vod)
    }
}
