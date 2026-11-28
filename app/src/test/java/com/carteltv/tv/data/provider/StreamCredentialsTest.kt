package com.carteltv.tv.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamCredentialsTest {

    @Test
    fun `live movie and series urls lose their credentials`() {
        for (kind in listOf("live", "movie", "series")) {
            val raw = "ref|Title|http://host.tv:8080/$kind/bob/s3cret/42.m3u8"
            val clean = StreamCredentials.redact(raw)
            assertFalse(clean.contains("s3cret"))
            assertFalse(clean.contains("bob"))
        }
    }

    @Test
    fun `restore round trips and ignores non xtream urls`() {
        val url = "https://host.tv/live/bob/s3cret/7.ts"
        val clean = StreamCredentials.redact(url)
        assertEquals(url, StreamCredentials.restore(clean, "bob", "s3cret"))
        val plain = "https://cdn.example.com/video.mp4"
        assertEquals(plain, StreamCredentials.redact(plain))
    }
}
