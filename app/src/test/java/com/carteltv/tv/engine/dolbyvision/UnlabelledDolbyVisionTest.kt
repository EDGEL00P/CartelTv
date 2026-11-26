package com.carteltv.tv.engine.dolbyvision

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UnlabelledDolbyVisionTest {

    @Test fun profile5IsSignalledAsFullRangeBt2020Pq() {
        val info = UnlabelledDolbyVision.profile5ColorInfo(existing = null)
        assertEquals(C.COLOR_SPACE_BT2020, info.colorSpace)
        assertEquals(C.COLOR_TRANSFER_ST2084, info.colorTransfer)
        assertEquals(C.COLOR_RANGE_FULL, info.colorRange)
        assertEquals(10, info.lumaBitdepth)
        assertEquals(10, info.chromaBitdepth)
    }

    @Test fun sdrLabellingOnTheHevcTrackIsReplaced() {
        val sdr = ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorTransfer(C.COLOR_TRANSFER_SDR)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .build()
        val info = UnlabelledDolbyVision.profile5ColorInfo(sdr)
        assertEquals(C.COLOR_SPACE_BT2020, info.colorSpace)
        assertEquals(C.COLOR_TRANSFER_ST2084, info.colorTransfer)
        assertEquals(C.COLOR_RANGE_FULL, info.colorRange)
    }

    @Test fun staticHdrInfoFromTheContainerIsKept() {
        val cll = byteArrayOf(0, 0, 3, 0x50, 0, 0x02, 0, 0x58)
        val existing = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setHdrStaticInfo(cll).build()
        assertArrayEquals(cll, UnlabelledDolbyVision.profile5ColorInfo(existing).hdrStaticInfo)
    }

    @Test fun hevcWithADolbyVisionCodecStringIsNotARelabelCandidate() {
        val candidate = UnlabelledDolbyVision.isCandidate("video/hevc", codecs = "hvc1.1.6.L93.B0", dolbyVisionConfigBytes = null)
        val alreadyLabelled = UnlabelledDolbyVision.isCandidate("video/hevc", codecs = "dvhe.05.06", dolbyVisionConfigBytes = null)
        val withConfig = UnlabelledDolbyVision.isCandidate("video/hevc", codecs = null, dolbyVisionConfigBytes = byteArrayOf(1, 0))
        assertEquals(listOf(true, false, false), listOf(candidate, alreadyLabelled, withConfig))
    }
}
