package com.carteltv.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {
    @Test
    fun encodeShortUrlProducesMatrix() {
        val m = QrEncoder.encode("http://192.168.1.10:9090/?t=abc123")
        assertNotNull(m)
        assertTrue(m!!.size in 21..41) // v1..v5 range (21..41)
        assertEquals(m.size, m[0].size)
    }

    @Test
    fun encodeEmptyProducesMatrix() {
        assertNotNull(QrEncoder.encode(""))
    }

    @Test
    fun encodeTooLongReturnsNull() {
        val long = "a".repeat(200)
        assertNull(QrEncoder.encode(long))
    }

    @Test
    fun matrixHasQuietBorderFinders() {
        val m = QrEncoder.encode("HELLO")!!
        // top-left finder: (0,0) dark
        assertTrue(m[0][0])
        assertTrue(m[0][6])
        assertTrue(m[6][0])
        assertTrue(m[6][6])
    }
}
