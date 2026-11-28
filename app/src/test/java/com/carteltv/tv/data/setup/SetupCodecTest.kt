package com.carteltv.tv.data.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class SetupCodecTest {
    private fun code(raw: ByteArray): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        return SetupCodec.base32Encode(bos.toByteArray()).chunked(8).joinToString("-")
    }

    @Test
    fun round_trips_a_small_snapshot() {
        val snap = SetupCodec.Snapshot(city = "Springfield", homeRows = listOf("a", "b"))
        assertEquals(snap, SetupCodec.decode(SetupCodec.encode(snap)))
    }

    @Test
    fun rejects_a_code_that_inflates_past_the_cap() {
        val bomb = code(ByteArray(8 * 1024 * 1024))
        try {
            SetupCodec.decode(bomb)
            fail("expected the oversize code to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("too large"))
        }
    }
}
