package com.carteltv.tv.data.network

import com.carteltv.tv.data.trakt.TraktClient
import com.carteltv.tv.data.xtream.XtreamClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class XtreamTraktTransportTest {
    private lateinit var original: HttpTransport

    @Before fun save() { original = HttpSupport.transport }
    @After fun restore() { HttpSupport.transport = original }

    private fun install(routes: Map<String, String>) = FakeHttpTransport(routes).also { HttpSupport.transport = it }

    @Test fun `xtream authenticate decodes user and server and encodes credentials`() = runBlocking {
        val fake = install(mapOf("player_api.php" to """{"user_info":{"username":"us er","max_connections":2,"auth":1},"server_info":{"url":"h","port":"80"}}"""))
        val (user, server) = XtreamClient("http://h:80", "us er", "p&w").authenticate()
        assertEquals(2, user?.maxConnections)
        assertEquals("80", server?.port)
        assertEquals("http://h:80/player_api.php?username=us+er&password=p%26w&action=", fake.requested.single())
    }

    @Test fun `xtream authenticate surfaces provider rejection status`() = runBlocking {
        install(mapOf("player_api.php" to """{"user_info":{"auth":0,"status":"Expired"}}"""))
        try {
            XtreamClient("http://h", "u", "p").authenticate()
            fail("expected rejection")
        } catch (e: IllegalStateException) {
            assertEquals("Expired", e.message)
        }
    }

    @Test fun `xtream authenticate rejects an expired line even when auth is 1`() = runBlocking {
        install(mapOf("player_api.php" to """{"user_info":{"auth":1,"status":"Expired"},"server_info":{}}"""))
        try {
            XtreamClient("http://h", "u", "p").authenticate()
            fail("expected rejection")
        } catch (e: IllegalStateException) {
            assertEquals("Expired", e.message)
        }
    }

    @Test fun `xtream authenticate accepts an active line`() = runBlocking {
        install(mapOf("player_api.php" to """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}"""))
        assertEquals("Active", XtreamClient("http://h", "u", "p").authenticate().first?.status)
    }

    @Test fun `xtream authenticate rejects payload without user_info`() = runBlocking {
        install(mapOf("player_api.php" to "{}"))
        try {
            XtreamClient("http://h", "u", "p").authenticate()
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("Unexpected auth payload", e.message)
        }
    }

    @Test fun `trakt pairing sends api key headers and decodes the device code`() = runBlocking {
        val fake = install(mapOf("/oauth/device/code" to """{"device_code":"dc","user_code":"ABCD1234","expires_in":300,"interval":3}"""))
        val code = TraktClient("client-id").beginPairing()
        assertEquals("ABCD1234", code.userCode)
        assertEquals(3, code.intervalSec)
        val h = fake.headersSeen.single()
        assertEquals("2", h["trakt-api-version"])
        assertEquals("client-id", h["trakt-api-key"])
        assertTrue("Authorization" !in h)
    }

    @Test fun `trakt authed get carries the bearer token`() {
        val fake = install(mapOf("/users/me" to "{}"))
        TraktClient("cid", "tok").getBlocking("/users/me")
        assertEquals("Bearer tok", fake.headersSeen.single()["Authorization"])
    }
}
