package com.carteltv.tv.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {
    /** Decodes our matrix back with ZXing's reader: the same check a phone camera performs. */
    private fun decode(matrix: Array<BooleanArray>): String {
        val quiet = 4
        val scale = 8 // phones see many pixels per module; 1px modules defeat the detector
        val n = matrix.size
        val total = (n + 2 * quiet) * scale
        val source =
            object : LuminanceSource(total, total) {
                override fun getRow(
                    y: Int,
                    row: ByteArray?,
                ): ByteArray {
                    val out = row ?: ByteArray(total)
                    val mr = y / scale - quiet
                    for (x in 0 until total) {
                        val mc = x / scale - quiet
                        val dark = mr in 0 until n && mc in 0 until n && matrix[mr][mc]
                        out[x] = if (dark) 0 else 255.toByte()
                    }
                    return out
                }

                override fun getMatrix(): ByteArray {
                    val out = ByteArray(total * total)
                    for (y in 0 until total) {
                        val row = getRow(y, null)
                        System.arraycopy(row, 0, out, y * total, total)
                    }
                    return out
                }
            }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private fun assertRoundTrip(text: String) {
        val m = QrEncoder.encode(text)
        assertNotNull("encode returned null for ${text.length} chars", m)
        assertEquals(text, decode(m!!))
    }

    @Test
    fun encodeShortUrlProducesMatrix() {
        val m = QrEncoder.encode("http://192.168.1.10:9090/?t=abc123")
        assertNotNull(m)
        assertEquals(m!!.size, m[0].size)
    }

    @Test
    fun roundTripShortAndEmpty() {
        assertRoundTrip("HELLO")
        assertRoundTrip("https://trakt.tv/activate")
    }

    @Test
    fun roundTripPairingUrls() {
        assertRoundTrip("http://192.168.1.10:9090/?t=abc123")
        assertRoundTrip("https://www.google.com/device?code=ABCD-EFGH&hl=en")
        assertRoundTrip("https://www.youtube.com/activate?code=ABCD-EFGH")
    }

    @Test
    fun roundTripBeyondOldInHouseLimit() {
        // The retired in-house encoder gave up past 106 bytes; real payloads must still encode.
        assertRoundTrip(
            "https://www.real-debrid.com/device?user_code=ABCD1234&verification_url=" +
                "https%3A%2F%2Freal-debrid.com%2Fdevice%3Fref%3Dcarteltv",
        )
        assertRoundTrip("x".repeat(500))
    }

    @Test
    fun roundTripUtf8() {
        assertRoundTrip("https://example.com/pair?name=Living-Room&code=OK-1234")
    }

    @Test
    fun matrixHasFinders() {
        val m = QrEncoder.encode("HELLO")!!
        assertTrue(m[0][0])
        assertTrue(m[0][6])
        assertTrue(m[6][0])
        assertTrue(m[6][6])
    }
}
