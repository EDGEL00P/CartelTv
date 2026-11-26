package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.network.FakeHttpTransport
import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.data.network.HttpTransport
import com.carteltv.tv.data.provider.Provider
import com.carteltv.tv.data.provider.ProviderHealth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The line's allowed_output_formats steer every live and timeshift link the client builds. */
class LiveOutputFormatTest {
    private lateinit var original: HttpTransport

    @Before fun save() { original = HttpSupport.transport }
    @After fun restore() { HttpSupport.transport = original }

    private fun client(formats: Set<String>?) = XtreamClient("http://h:80", "u", "p").also { it.allowedFormats = formats }

    @Test fun `a line that never said keeps the hls default`() {
        assertTrue(client(null).liveUrl(5).endsWith("/5.m3u8"))
    }

    @Test fun `a ts line gets ts links`() {
        assertTrue(client(setOf("ts")).liveUrl(5).endsWith("/5.ts"))
    }

    @Test fun `a line allowing both prefers ts to ride panel load`() {
        assertTrue(client(setOf("m3u8", "ts")).liveUrl(5).endsWith("/5.ts"))
    }

    @Test fun `an hls-only line keeps m3u8`() {
        assertTrue(client(setOf("m3u8")).liveUrl(5).endsWith("/5.m3u8"))
        assertTrue(client(setOf("hls")).liveUrl(5).endsWith("/5.m3u8"))
    }

    @Test fun `explicit hls wins over the line's formats`() {
        assertTrue(client(setOf("ts")).liveUrl(5, hls = true).endsWith("/5.m3u8"))
        assertTrue(client(setOf("m3u8")).liveUrl(5, hls = false).endsWith("/5.ts"))
    }

    @Test fun `timeshift links inherit the choice`() {
        val c = client(setOf("ts"))
        assertTrue(c.timeshiftUrl(5, 1_000L, 2_000L).endsWith("/5.ts?utc=1&ltc=2"))
        assertTrue(c.timeshiftPathUrl(5, 1_000L, 60).endsWith("/5.ts"))
    }

    @Test fun `authenticate captures the declared formats`() = runBlocking {
        HttpSupport.transport = FakeHttpTransport(mapOf(
            "player_api.php" to """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["TS","m3u8"]},"server_info":{}}""",
        ))
        val c = XtreamClient("http://h", "u", "p")
        c.authenticate()
        assertEquals(setOf("ts", "m3u8"), c.allowedFormats)
        assertTrue(c.liveUrl(5).endsWith("/5.ts"))
    }

    @Test fun `normalize drops junk and keeps null when the panel said nothing`() {
        assertNull(normalizeOutputFormats(null))
        assertNull(normalizeOutputFormats(emptyList()))
        assertNull(normalizeOutputFormats(listOf(" ")))
        assertEquals(setOf("ts", "m3u8"), normalizeOutputFormats(listOf(" TS ", "m3u8", "")))
    }

    @Test fun `restored clients inherit the last login's formats without signing in`() {
        val p = Provider("id", "n", "http://h", "u", "p", health = ProviderHealth(formats = listOf("ts")))
        assertTrue(ProviderSession.clientFor(p).liveUrl(5).endsWith("/5.ts"))
    }
}
