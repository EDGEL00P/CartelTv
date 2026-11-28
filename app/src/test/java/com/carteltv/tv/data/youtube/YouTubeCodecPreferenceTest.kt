package com.carteltv.tv.data.youtube

import com.carteltv.tv.data.youtube.YouTubeApiClient.FormatCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeCodecPreferenceTest {

    private fun f(itag: String, mime: String, h: Int = 1080) =
        FormatCandidate(itag, "https://x/$itag", mime, h, 30, 1_000_000, width = h * 16 / 9)

    private val formats = listOf(
        f("137", "video/mp4; codecs=\"avc1.640028\""),
        f("248", "video/webm; codecs=\"vp9\""),
        f("399", "video/mp4; codecs=\"av01.0.08M.08\""),
    )

    @Test
    fun automatic_keeps_avc_first() {
        assertEquals("137", YouTubeApiClient.pickBestVideo(formats, 1080)!!.itag)
    }

    @Test
    fun preferred_codec_wins_ties_only_at_same_resolution() {
        assertEquals("248", YouTubeApiClient.pickBestVideo(formats, 1080, "vp9")!!.itag)
        assertEquals("399", YouTubeApiClient.pickBestVideo(formats, 1080, "av01")!!.itag)
        val withHigher = formats + f("313", "video/webm; codecs=\"vp09.00.50.08\"", 2160)
        assertEquals("313", YouTubeApiClient.pickBestVideo(withHigher, 2160, "av01")!!.itag)
    }
}
