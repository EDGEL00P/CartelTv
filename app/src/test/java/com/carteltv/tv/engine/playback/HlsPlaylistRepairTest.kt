package com.carteltv.tv.engine.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HlsRepairTest {

    @Test fun stripsBom() {
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3", HlsPlaylistRepair.fix("\uFEFF#EXTM3U\n#EXT-X-VERSION:3"))
    }

    @Test fun addsMissingHeader() {
        val fixed = HlsPlaylistRepair.fix("#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts")!!
        assertTrue(fixed.startsWith("#EXTM3U\n"))
    }

    @Test fun rejectsNonPlaylists() {
        assertNull(HlsPlaylistRepair.fix("<html>nope</html>"))
        assertNull(HlsPlaylistRepair.fix(""))
    }

    @Test fun addsBandwidthToVariants() {
        val fixed = HlsPlaylistRepair.fix("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x720\nhi.m3u8")!!
        assertTrue(fixed.contains("#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1280x720"))
    }

    @Test fun keepsAnExistingBandwidth() {
        val src = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlo.m3u8"
        assertEquals(src, HlsPlaylistRepair.fix(src))
    }

    @Test fun addsNameAndGroupToRenditions() {
        val fixed = HlsPlaylistRepair.fix("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"a.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8")!!
        assertTrue(fixed.contains("NAME=\"Main\""))
        assertTrue(fixed.contains("GROUP-ID=\"default\""))
    }

    @Test fun detectsTransportStreamByTwoSyncBytes() {
        val ts = ByteArray(376).also { it[0] = 0x47; it[188] = 0x47 }
        assertTrue(HlsPlaylistRepair.isTs(ts))
        assertFalse(HlsPlaylistRepair.isTs("G".toByteArray() + ByteArray(300)))
        assertFalse(HlsPlaylistRepair.isTs("#EXTM3U".toByteArray()))
    }

    @Test fun findsTsMarkerAnywhereInTheCauseChain() {
        assertTrue(RuntimeException("outer", RuntimeException("mid", HlsBodyIsTs())).servedTsForHls())
        assertFalse(RuntimeException("outer", IOException("net")).servedTsForHls())
    }
}
