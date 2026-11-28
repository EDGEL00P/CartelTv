package com.carteltv.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private fun parse(vararg lines: String) = M3uParser.parse(lines.asSequence()).entries

    @Test fun vlcOptionsBecomeRequestHeaders() {
        val e = parse(
            "#EXTM3U",
            "#EXTINF:-1,News",
            "#EXTVLCOPT:http-user-agent=CartelTV test",
            "#EXTVLCOPT:http-referrer=https://example.com/",
            "#EXTVLCOPT:http-header=X-Token: abc123",
            "http://example.com/live.ts",
        ).single()
        assertEquals("CartelTV test", e.headers["User-Agent"])
        assertEquals("https://example.com/", e.headers["Referer"])
        assertEquals("abc123", e.headers["X-Token"])
        assertNull(e.drm)
    }

    @Test fun extHttpHeadersAreReadWithoutAJsonLibrary() {
        val e = parse(
            "#EXTM3U",
            """#EXTHTTP:{"header":{"User-Agent":"ExtHttp UA","Referer":"https://ref.example"},"ignore":"me"}""",
            "#EXTINF:-1,News",
            "http://example.com/live.ts",
        ).single()
        assertEquals("ExtHttp UA", e.headers["User-Agent"])
        assertEquals("https://ref.example", e.headers["Referer"])
    }

    @Test fun kodiLicenseFieldsBecomeDrmInfoInEitherOrder() {
        val typeProp = "#KODIPROP:inputstream.adaptive.license_type=widevine"
        val keyProp = """#KODIPROP:inputstream.adaptive.license_key=https://lic.example/key||Authorization=Bearer%20abc&X-Id=7||keyid123"""
        for (order in listOf(listOf(typeProp, keyProp), listOf(keyProp, typeProp))) {
            val e = parse(
                "#EXTM3U",
                "#EXTINF:-1,Protected",
                *order.toTypedArray(),
                "http://example.com/stream.mpd",
            ).single()
            val drm = e.drm!!
            assertEquals("widevine", drm.scheme)
            assertEquals("https://lic.example/key", drm.licenseUrl)
            assertEquals("Bearer abc", drm.headers["Authorization"])
            assertEquals("keyid123", drm.keyId)
        }
    }

    @Test fun drmDefaultsToWidevineWhenOnlyTheKeyIsGiven() {
        val e = parse(
            "#EXTM3U",
            "#EXTINF:-1,Protected",
            "#KODIPROP:inputstream.adaptive.license_key=https://lic.example/key",
            "http://example.com/stream.mpd",
        ).single()
        assertEquals("widevine", e.drm!!.scheme)
    }

    @Test fun metadataDoesNotLeakIntoTheNextEntry() {
        val entries = parse(
            "#EXTM3U",
            "#EXTINF:-1,Protected",
            "#KODIPROP:inputstream.adaptive.license_key=https://lic.example/key",
            "http://example.com/a.mpd",
            "#EXTINF:-1,Plain",
            "http://example.com/b.ts",
        )
        assertEquals(2, entries.size)
        assertEquals("https://lic.example/key", entries[0].drm!!.licenseUrl)
        assertNull(entries[1].drm)
        assertEquals(emptyMap<String, String>(), entries[1].headers)
    }
}
