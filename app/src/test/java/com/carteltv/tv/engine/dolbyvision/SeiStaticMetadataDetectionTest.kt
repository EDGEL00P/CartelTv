package com.carteltv.tv.engine.dolbyvision

import com.carteltv.tv.engine.hdr.Hdr10SeiInjector
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Both "does the base layer carry HDR10 static metadata" detectors must agree on every shape of SEI NAL. */
class SeiStaticMetadataDetectionTest {

    private val MASTERING_DISPLAY = 137
    private val CONTENT_LIGHT_LEVEL = 144

    /** A prefix-SEI NAL (type 39) holding [messages] as (payloadType, payload) pairs, then the trailing bits. */
    private fun seiNal(vararg messages: Pair<Int, ByteArray>, escape: Boolean = true): ByteArray {
        val rbsp = ArrayList<Byte>()
        for ((type, payload) in messages) {
            var rest = type
            while (rest >= 0xFF) { rbsp += 0xFF.toByte(); rest -= 0xFF }
            rbsp += rest.toByte()
            rest = payload.size
            while (rest >= 0xFF) { rbsp += 0xFF.toByte(); rest -= 0xFF }
            rbsp += rest.toByte()
            payload.forEach { rbsp += it }
        }
        rbsp += 0x80.toByte()
        val body = rbsp.toByteArray()
        return byteArrayOf(0x4E, 0x01) + if (escape) escapeRbsp(body) else body
    }

    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var zeros = 0
        for (b in rbsp) {
            if (zeros == 2 && (b.toInt() and 0xFF) <= 3) { out += 0x03.toByte(); zeros = 0 }
            out += b
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun sample(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            val length = nal.size
            out.write(length ushr 24)
            out.write((length ushr 16) and 0xFF)
            out.write((length ushr 8) and 0xFF)
            out.write(length and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun assertBothDetectors(sample: ByteArray, expected: Boolean) {
        assertEquals(
            expected,
            HevcDolbyVisionRpuRemover.containsHdr10StaticMetadataSei(sample, sample.size, nalUnitLengthFieldLength = 4),
        )
        assertEquals(
            expected,
            Hdr10SeiInjector.hasHdr10StaticSei(
                sample, sample.size, annexB = false, nalLengthFieldLength = 4,
            ),
        )
    }

    @Test fun staticMetadataBehindAnotherMessageIsFound() {
        assertBothDetectors(
            sample(
                seiNal(
                    1 to byteArrayOf(0x11, 0x22),
                    MASTERING_DISPLAY to byteArrayOf(1, 2, 3, 4, 5),
                    CONTENT_LIGHT_LEVEL to byteArrayOf(6, 7),
                ),
            ),
            expected = true,
        )
    }

    @Test fun emulationPreventionBytesInsideAnEarlierMessageDoNotDesynchroniseTheWalk() {
        // Sixteen zero bytes escape to five `00 00 03` groups; the payload size still counts sixteen.
        assertBothDetectors(
            sample(seiNal(5 to ByteArray(16), MASTERING_DISPLAY to byteArrayOf(1, 2, 3, 4, 5))),
            expected = true,
        )
    }

    @Test fun leadingStaticMetadataIsStillFound() {
        assertBothDetectors(
            sample(seiNal(CONTENT_LIGHT_LEVEL to byteArrayOf(6, 7), MASTERING_DISPLAY to byteArrayOf(1, 2, 3, 4, 5))),
            expected = true,
        )
    }

    @Test fun otherMessagesAloneAreNotStaticMetadata() {
        assertBothDetectors(sample(seiNal(1 to byteArrayOf(0x11, 0x22), 5 to ByteArray(16))), expected = false)
    }

    @Test fun aNalWithoutMetadataDoesNotHideTheOneThatHasIt() {
        assertBothDetectors(
            sample(seiNal(1 to byteArrayOf(0x11)), seiNal(CONTENT_LIGHT_LEVEL to byteArrayOf(6, 7))),
            expected = true,
        )
    }
}
