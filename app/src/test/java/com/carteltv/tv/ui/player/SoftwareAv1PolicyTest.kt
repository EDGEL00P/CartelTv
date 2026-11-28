package com.carteltv.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareAv1PolicyTest {

    private fun av1(width: Int, height: Int, codecs: String? = "av01.0.15M.08") = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_AV1)
        .setWidth(width)
        .setHeight(height)
        .setCodecs(codecs)
        .build()

    private fun av1WithConfig(config: ByteArray) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_AV1)
        .setWidth(1920)
        .setHeight(1080)
        .setInitializationData(listOf(config))
        .build()

    private fun support(format: Format, hardwareHandles: Boolean, decoderSupport: Int = C.FORMAT_HANDLED) =
        SoftwareAv1Policy.formatSupport(format, decoderSupport) { hardwareHandles }

    @Test fun takesEightBitUpTo1080pWhenNoHardwareDecodesIt() {
        assertEquals(C.FORMAT_HANDLED, support(av1(1920, 1088), hardwareHandles = false))
        assertEquals(C.FORMAT_HANDLED, support(av1(1920, 1080), hardwareHandles = false))
        assertEquals(C.FORMAT_HANDLED, support(av1(1280, 720), hardwareHandles = false))
    }

    @Test fun declinesWhateverAHardwareDecoderTakes() {
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(av1(1920, 1080), hardwareHandles = true))
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(av1(1280, 720), hardwareHandles = true))
    }

    @Test fun declinesOverSizeWhetherOrNotHardwareExists() {
        val uhd = av1(3840, 2160)
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(uhd, hardwareHandles = false))
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(uhd, hardwareHandles = true))
    }

    @Test fun declinesHighBitDepth() {
        val tenBit = av1(1280, 720, codecs = "av01.0.15M.10")
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(tenBit, hardwareHandles = false))

        val hdr = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(1280)
            .setHeight(720)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build())
            .build()
        assertTrue(SoftwareAv1Policy.isHighBitDepth(hdr))
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, support(hdr, hardwareHandles = false))
    }

    @Test fun keepsTheDecodersOwnVerdictWhenItCannotDecode() {
        val hd = av1(1280, 720)
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, support(hd, hardwareHandles = false, decoderSupport = C.FORMAT_UNSUPPORTED_TYPE))
        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, support(hd, hardwareHandles = false, decoderSupport = C.FORMAT_EXCEEDS_CAPABILITIES))
    }

    @Test fun aStreamWithNoDeclaredSizeIsNotJudged() {
        val unknownSize = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs("av01.0.15M.08").build()
        assertFalse(SoftwareAv1Policy.exceedsSoftwareSize(unknownSize))
        assertEquals(C.FORMAT_HANDLED, support(unknownSize, hardwareHandles = false))
    }

    @Test fun bitDepthComesFromColorInfoThenTheCodecStringThenTheConfigRecord() {
        assertEquals(10, SoftwareAv1Policy.bitDepth(av1(1920, 1080, codecs = "av01.0.15M.10")))
        assertEquals(8, SoftwareAv1Policy.bitDepth(av1(1920, 1080, codecs = "hvc1.1.6.L93.B0,av01.0.05M.08")))

        assertEquals(8, SoftwareAv1Policy.bitDepth(av1WithConfig(byteArrayOf(0x81.toByte(), 0x00, 0x00))))
        assertEquals(8, SoftwareAv1Policy.bitDepth(av1WithConfig(byteArrayOf(0x81.toByte(), 0x00, 0x10))))
        assertEquals(10, SoftwareAv1Policy.bitDepth(av1WithConfig(byteArrayOf(0x81.toByte(), 0x00, 0x40))))
        assertEquals(12, SoftwareAv1Policy.bitDepth(av1WithConfig(byteArrayOf(0x81.toByte(), 0x00, 0x60))))

        // An OBU stream instead of a config record: the marker bit is clear, so nothing is claimed.
        assertNull(SoftwareAv1Policy.bitDepth(av1WithConfig(byteArrayOf(0x0A.toByte(), 0x0A, 0x00, 0x00))))
    }
}
