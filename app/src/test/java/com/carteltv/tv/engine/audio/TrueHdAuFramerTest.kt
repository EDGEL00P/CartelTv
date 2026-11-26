package com.carteltv.tv.engine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TrueHdAuFramerTest {

    /** An access unit of [words] 16-bit words; the low 12 bits of the first word hold the length. */
    private fun au(words: Int, fill: Int): ByteArray {
        val b = ByteArray(words * 2) { fill.toByte() }
        b[0] = ((words shr 8) and 0x0F).toByte()
        b[1] = (words and 0xFF).toByte()
        return b
    }

    private fun feedAll(f: TrueHdAudioFramer, data: ByteArray, off: Int = 0, len: Int = data.size - off): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        f.feed(data, off, len) { out += it }
        return out
    }

    @Test fun splitsBatchedUnits() {
        val a = au(10, 1); val b = au(20, 2); val c = au(8, 3)
        val out = feedAll(TrueHdAudioFramer(), a + b + c)
        assertEquals(3, out.size)
        assertArrayEquals(a, out[0]); assertArrayEquals(b, out[1]); assertArrayEquals(c, out[2])
    }

    @Test fun carriesAPartialUnitAcrossCalls() {
        val f = TrueHdAudioFramer()
        val a = au(30, 7)
        assertEquals(0, feedAll(f, a, 0, 25).size)
        val out = feedAll(f, a, 25, a.size - 25)
        assertEquals(1, out.size)
        assertArrayEquals(a, out[0])
    }

    @Test fun carriesALoneHeaderByte() {
        val f = TrueHdAudioFramer()
        val a = au(12, 5)
        assertEquals(0, feedAll(f, a, 0, 1).size)
        assertArrayEquals(a, feedAll(f, a, 1, a.size - 1).single())
    }

    @Test fun honoursTheStartOffset() {
        val a = au(9, 4)
        val padded = ByteArray(5) + a
        assertArrayEquals(a, feedAll(TrueHdAudioFramer(), padded, 5).single())
    }

    @Test fun resetDropsTheCarry() {
        val f = TrueHdAudioFramer()
        val a = au(30, 7)
        feedAll(f, a, 0, 20)
        f.reset()
        assertEquals(1, feedAll(f, a).size)
    }

    @Test fun malformedZeroLengthEmitsNothingAndDoesNotLoop() {
        assertEquals(0, feedAll(TrueHdAudioFramer(), ByteArray(16)).size)
    }
}
