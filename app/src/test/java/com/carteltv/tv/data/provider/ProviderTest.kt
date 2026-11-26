package com.carteltv.tv.data.provider

import com.carteltv.tv.data.preferences.Credentials
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.ui.settings.accountSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTest {
    @Test fun hostLabelStripsSchemePortAndPath() {
        assertEquals("example.com", Provider.hostLabel("http://example.com:8080/"))
        assertEquals("a.b.tv", Provider.hostLabel("https://a.b.tv/path"))
        assertEquals("plain", Provider.hostLabel("plain"))
    }

    @Test fun newProvidersGetDistinctIdsAndHostName() {
        val a = Provider.of(Credentials("http://x.tv:80", "u", "p"))
        val b = Provider.of(Credentials("http://x.tv:80", "u", "p"))
        assertNotEquals(a.id, b.id)
        assertEquals("x.tv", a.name)
    }

    @Test fun summaryReportsDaysLeftAndConnections() {
        val now = 1_700_000_000_000L
        val u = XtreamClient.UserInfo("bob", maxConnections = 2, activeCons = 1, expiryDate = ((now / 1000) + 10 * 86_400).toString())
        val s = accountSummary(u, now)
        assertTrue(s, s.contains("10 days left"))
        assertTrue(s, s.contains("1 of 2"))
        assertTrue(s, s.contains("bob"))
    }

    @Test fun summaryHandlesExpiredAndNoExpiry() {
        val now = 1_700_000_000_000L
        assertTrue(accountSummary(XtreamClient.UserInfo("a", expiryDate = "1600000000"), now).contains("Expired"))
        assertTrue(accountSummary(XtreamClient.UserInfo("a", expiryDate = null), now).contains("No expiry"))
        assertTrue(accountSummary(XtreamClient.UserInfo("a", expiryDate = "0"), now).contains("No expiry"))
        assertEquals("Signed in.", accountSummary(null, now))
    }

    @Test fun summaryListsTheLinesStreamFormatsWhenDeclared() {
        val declared = accountSummary(XtreamClient.UserInfo("bob", allowedOutputFormats = listOf("ts", "m3u8")))
        assertTrue(declared, declared.contains("TS · HLS"))
        val silent = accountSummary(XtreamClient.UserInfo("bob"))
        assertTrue(silent, !silent.contains("Formats"))
    }
}
