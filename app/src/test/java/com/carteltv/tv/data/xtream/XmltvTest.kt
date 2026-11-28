package com.carteltv.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmltvTest {
    @Test
    fun zone_offsets_shift_to_utc() {
        val utc = XmltvTime.parse("20261121183000 +0000")!!
        assertEquals(utc, XmltvTime.parse("20261121183000"))
        assertEquals(utc - 3_600_000L, XmltvTime.parse("20261121183000 +0100"))
        assertEquals(utc + 5 * 3_600_000L, XmltvTime.parse("20261121183000 -0500"))
        assertEquals(utc + 30 * 60_000L, XmltvTime.parse("20261121183000 -0030"))
    }

    @Test
    fun seconds_are_optional_and_garbage_is_rejected() {
        val m = XmltvTime.parse("202611211830 +0000")!!
        assertEquals(30_000L, XmltvTime.parse("20261121183030 +0000")!! - m)
        assertNull(XmltvTime.parse(null))
        assertNull(XmltvTime.parse(""))
        assertNull(XmltvTime.parse("2026-11-21"))
    }

    @Test
    fun guide_url_prefers_valid_custom_else_provider() {
        val c = com.carteltv.tv.data.xtream.XtreamClient("http://h:80", "us er", "p&w")
        assertEquals("http://h:80/xmltv.php?username=us+er&password=p%26w", com.carteltv.tv.data.xtream.EpgSync.guideUrl(c, ""))
        assertEquals("http://h:80/xmltv.php?username=us+er&password=p%26w", com.carteltv.tv.data.xtream.EpgSync.guideUrl(c, "ftp://x/y.xml"))
        assertEquals("https://g.example/epg.xml", com.carteltv.tv.data.xtream.EpgSync.guideUrl(c, "https://g.example/epg.xml"))
    }

    @Test
    fun refresh_policy() {
        val h = 60 * 60_000L
        assertTrue(EpgFreshness.due(100 * h, 0, 0))
        assertFalse(EpgFreshness.due(100 * h, 98 * h, 0))
        assertTrue(EpgFreshness.due(100 * h, 90 * h, 0))
        assertFalse(EpgFreshness.due(100 * h, 90 * h, 100 * h - 10 * 60_000L))
        assertTrue(EpgFreshness.due(100 * h, 90 * h, 100 * h - 31 * 60_000L))
    }
}
