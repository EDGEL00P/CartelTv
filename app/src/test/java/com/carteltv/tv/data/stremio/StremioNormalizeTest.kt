package com.carteltv.tv.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Test

class StremioNormalizeTest {
    @Test fun `stremio scheme becomes https`() {
        assertEquals("https://a.example/x/manifest.json", StremioClient.normalizeBaseUrl("stremio://a.example/x/manifest.json"))
        assertEquals("https://a.example/manifest.json", StremioClient.normalizeBaseUrl("STREMIO://a.example/manifest.json"))
    }

    @Test fun `http urls are kept and bare hosts get https`() {
        assertEquals("http://a.example/m", StremioClient.normalizeBaseUrl(" http://a.example/m/ "))
        assertEquals("https://a.example", StremioClient.normalizeBaseUrl("a.example"))
    }
}
