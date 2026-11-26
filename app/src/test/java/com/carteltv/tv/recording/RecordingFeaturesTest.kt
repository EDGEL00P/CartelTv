package com.carteltv.tv.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingFeaturesTest {

    @Test fun smb_plainAnonymous() {
        val s = SmbPaths.parse("smb://nas.local/media/recording1.ts")
        assertEquals("nas.local", s.host)
        assertEquals(445, s.port)
        assertNull(s.user)
        assertEquals("media", s.share)
        assertEquals("recording1.ts", s.path)
    }

    @Test fun smb_userPassDomainAndPort() {
        val s = SmbPaths.parse("smb://WORKGROUP;matt:secret@192.168.1.10:4445/videos/live/x.ts")
        assertEquals("192.168.1.10", s.host)
        assertEquals(4445, s.port)
        assertEquals("WORKGROUP", s.domain)
        assertEquals("matt", s.user)
        assertEquals("secret", s.pass)
        assertEquals("videos", s.share)
        assertEquals("live\\x.ts", s.path)
    }

    @Test fun smb_nestedPathSpaces() {
        val s = SmbPaths.parse("smb://box/share/a b/c d.ts")
        assertEquals("a b\\c d.ts", s.path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun smb_missingFileRejected() {
        SmbPaths.parse("smb://host/share")
    }

    @Test(expected = IllegalArgumentException::class)
    fun smb_notSmbRejected() {
        SmbPaths.parse("http://host/x")
    }

    @Test fun pickVariant_bestBandwidthWins() {
        val lines = listOf(
            "#EXTM3U",
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360",
            "low/index.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080",
            "high/index.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720",
            "mid/index.m3u8",
        )
        assertEquals("high/index.m3u8", HlsCapture.pickVariant(lines))
    }

    @Test fun pickVariant_noBandwidthFallsBackToFirst() {
        val lines = listOf(
            "#EXTM3U",
            "#EXT-X-STREAM-INF:RESOLUTION=1920x1080",
            "only/index.m3u8",
        )
        assertEquals("only/index.m3u8", HlsCapture.pickVariant(lines))
    }

    @Test fun pickVariant_skipsCommentsBetweenTags() {
        val lines = listOf(
            "#EXT-X-STREAM-INF:BANDWIDTH=1000000",
            "# some comment",
            "",
            "real/index.m3u8",
        )
        assertEquals("real/index.m3u8", HlsCapture.pickVariant(lines))
    }

    @Test fun resolve_siblingRelative() {
        assertEquals("http://h/p/seg1.ts", HlsCapture.resolve("http://h/p/list.m3u8", "seg1.ts"))
    }

    @Test fun resolve_rootAbsolute() {
        assertEquals("http://h/abs.ts", HlsCapture.resolve("http://h/p/list.m3u8", "/abs.ts"))
    }

    @Test fun resolve_absolutePassthrough() {
        assertEquals("https://o/x.ts", HlsCapture.resolve("http://h/p/l.m3u8", "https://o/x.ts"))
    }
}
