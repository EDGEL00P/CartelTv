package com.carteltv.tv.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeDeepLinkTest {
    @Test fun `yt reference names its video`() {
        val request = YouTubeDeepLink.requestFor("yt:wWXs9eKfllg", "Busting 33 MYTHS In GTA 6!")
        assertEquals("wWXs9eKfllg", request?.videoId)
        assertEquals("Busting 33 MYTHS In GTA 6!", request?.title)
    }

    @Test fun `provider references are not YouTube`() {
        assertNull(YouTubeDeepLink.requestFor("movie:1523674", "Unbearable Christmas"))
        assertNull(YouTubeDeepLink.requestFor("live:18704", "BBC"))
    }

    @Test fun `empty id is rejected`() {
        assertNull(YouTubeDeepLink.requestFor("yt:", "x"))
    }

    @Test fun `a posted request is delivered once`() {
        YouTubeDeepLink.post(YouTubeDeepLink.Request("abc", "t"))
        assertNotNull(YouTubeDeepLink.take())
        assertNull(YouTubeDeepLink.take())
    }
}
