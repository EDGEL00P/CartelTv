package com.carteltv.tv.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsUpgradeTransportTest {
    @Test fun upgradesPublicHostAndPrefersHttps() = runBlocking {
        val fake = FakeHttpTransport(mapOf("https://api.example.com/x" to "ok"))
        val t = HttpsUpgradeTransport(fake)
        assertEquals("ok", t.get("http://api.example.com/x", emptyMap(), 8000, 8000))
        assertEquals(listOf("https://api.example.com/x"), fake.requested)
    }

    @Test fun fallsBackToHttpAndRemembersHost() = runBlocking {
        val fake = FakeHttpTransport(mapOf("http://iptv.example.com/p" to "plain"))
        val t = HttpsUpgradeTransport(fake)
        assertEquals("plain", t.get("http://iptv.example.com/p", emptyMap(), 8000, 8000))
        assertEquals("plain", t.get("http://iptv.example.com/p", emptyMap(), 8000, 8000))
        assertEquals(
            listOf("https://iptv.example.com/p", "http://iptv.example.com/p", "http://iptv.example.com/p"),
            fake.requested,
        )
    }

    @Test fun neverUpgradesLanPortsIpsOrPosts() = runBlocking {
        for (u in listOf("http://192.168.1.5/a", "http://hue.local/a", "http://nas/a", "http://example.com:8080/a", "http://1.2.3.4/a")) {
            assertNull(u, HttpsUpgradeTransport.upgradeHost(u))
        }
        val fake = FakeHttpTransport(mapOf("http://api.example.com/p" to "x"))
        HttpsUpgradeTransport(fake).request("http://api.example.com/p", "POST", emptyMap(), "b", null, 1, 1)
        assertEquals(listOf("http://api.example.com/p"), fake.requested)
    }
}
