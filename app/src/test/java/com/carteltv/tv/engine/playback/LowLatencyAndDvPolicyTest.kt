package com.carteltv.tv.engine.playback

import android.media.MediaCodecInfo.CodecProfileLevel
import android.view.Display
import com.carteltv.tv.ui.player.LowLatencyDecode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLatencyAndDvPolicyTest {

    private fun ll(
        enabled: Boolean = true,
        live: Boolean = true,
        sdk: Int = 30,
        video: Boolean = true,
        secure: Boolean = false,
        supported: Boolean = true,
    ) = LowLatencyDecode.shouldRequest(enabled, live, sdk, video, secure, supported)

    @Test fun lowLatencyRequestedWhenEverythingAllows() = assertTrue(ll())

    @Test fun lowLatencyNeedsEveryCondition() {
        assertFalse(ll(enabled = false))
        assertFalse(ll(live = false))
        assertFalse(ll(sdk = 29))
        assertFalse(ll(video = false))
        assertFalse(ll(secure = true))
        assertFalse(ll(supported = false))
    }

    private val dv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
    private val hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10
    private val p7 = CodecProfileLevel.DolbyVisionProfileDvheDtb
    private val p5 = CodecProfileLevel.DolbyVisionProfileDvheStn
    private val p8 = CodecProfileLevel.DolbyVisionProfileDvheSt

    @Test fun conversionStaysActiveForEveryCapability() {
        listOf(
            VideoCaps(null, null),
            VideoCaps(emptySet(), emptySet()),
            VideoCaps(setOf(hdr10), setOf(p8)),
            VideoCaps(setOf(dv, hdr10), setOf(p5, p7, p8)),
        ).forEach { assertTrue(DvPolicy.resolve(it, allowNativeProfile7 = true).active) }
    }

    @Test fun unknownCapabilityKeepsLegacyBehaviour() {
        val cfg = DvPolicy.resolve(VideoCaps(null, null), allowNativeProfile7 = true)
        assertFalse(cfg.nativeProfile7)
        assertFalse(cfg.relabelUnlabelledP5)
        assertTrue(cfg.shouldConvert(7))
    }

    @Test fun nativeProfile7NeedsOptInDecoderAndDisplay() {
        val full = VideoCaps(setOf(dv), setOf(p7))
        assertTrue(DvPolicy.resolve(full, allowNativeProfile7 = true).nativeProfile7)
        assertFalse(DvPolicy.resolve(full, allowNativeProfile7 = false).nativeProfile7)
        assertFalse(DvPolicy.resolve(VideoCaps(setOf(hdr10), setOf(p7)), true).nativeProfile7)
        assertFalse(DvPolicy.resolve(VideoCaps(setOf(dv), setOf(p5, p8)), true).nativeProfile7)
    }

    @Test fun nativeProfile7TurnsOffConversionOfProfile7() {
        val native = DvPolicy.resolve(VideoCaps(setOf(dv), setOf(p7)), true)
        assertFalse(native.shouldConvert(7))
        assertTrue(DvPolicy.resolve(VideoCaps(setOf(dv), setOf(p8)), true).shouldConvert(7))
    }

    @Test fun relabelProfile5OnlyWithAProfile5Decoder() {
        assertTrue(DvPolicy.resolve(VideoCaps(setOf(hdr10), setOf(p5)), false).relabelUnlabelledP5)
        assertFalse(DvPolicy.resolve(VideoCaps(setOf(hdr10), setOf(p8)), false).relabelUnlabelledP5)
    }

    @Test fun capabilityFlagsReadTheSets() {
        val c = VideoCaps(setOf(dv, hdr10), setOf(p5, p7))
        assertTrue(c.displayHasDolbyVision && c.decoderHasDvProfile5 && c.decoderHasDvProfile7)
        assertEquals(false, VideoCaps(null, null).displayHasDolbyVision)
    }
}
