package com.carteltv.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The bundled logo files must match what ChannelLogos reads. An asset name the Android build renames
 * (it strips ".gz") or an index pointing past the end of the pack would silently show no logos.
 */
class LogoAssetsTest {

    private fun asset(name: String): File =
        listOf("src/main/assets", "app/src/main/assets").map { File(it, name) }.firstOrNull { it.exists() }
            ?: error("$name not found under src/main/assets")

    @Test fun assetNamesAreNotRenamedByTheBuild() {
        assertTrue(!ChannelLogos.ASSET.endsWith(".gz"))
        assertTrue(asset(ChannelLogos.ASSET).length() > 100_000)
        assertTrue(asset(ChannelLogos.PACK_ASSET).length() > 100_000)
    }

    @Test fun packHeaderAndOffsetsAreConsistent() {
        val bytes = asset(ChannelLogos.PACK_ASSET).readBytes()
        val buf = ByteBuffer.wrap(bytes)
        assertEquals(ChannelLogos.PACK_MAGIC, buf.int)
        val count = buf.int
        val offsets = IntArray(count + 1) { buf.int }
        assertEquals(0, offsets[0])
        for (i in 0 until count) assertTrue("offset $i not increasing", offsets[i] <= offsets[i + 1])
        assertEquals(bytes.size.toLong(), 8L + 4L * (count + 1) + offsets[count])
    }

    @Test fun everyIndexEntryPointsAtARealLogo() {
        val count = ByteBuffer.wrap(asset(ChannelLogos.PACK_ASSET).readBytes(), 4, 4).int
        var packed = 0
        asset(ChannelLogos.ASSET).forEachLine { line ->
            val p = line.split('\t')
            assertEquals("bad line: $line", 3, p.size)
            val v = p[2]
            if (v.startsWith("#")) {
                val id = v.drop(1).toInt()
                assertTrue("id $id out of range", id in 0 until count)
                packed++
            } else {
                assertTrue("not a url: $v", v.startsWith("http"))
            }
        }
        assertTrue(packed > 0)
    }
}
