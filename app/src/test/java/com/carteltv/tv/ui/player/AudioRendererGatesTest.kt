package com.carteltv.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AudioRendererGatesTest {

    /** A sink that takes exactly [mimes] as bitstreams and nothing else. */
    private fun sinkTaking(vararg mimes: String): AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)
    ) { _, method, args ->
        val passesThrough = (args?.get(0) as Format).sampleMimeType in mimes
        when (method.name) {
            "getFormatSupport" ->
                if (passesThrough) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
            "supportsFormat" -> passesThrough
            else -> throw UnsupportedOperationException(method.name)
        }
    } as AudioSink

    private fun audio(mime: String) = Format.Builder().setSampleMimeType(mime).build()

    private fun yields(mime: String, sink: AudioSink, mediaCodecHandles: Boolean) =
        ffmpegYieldsFormat(audio(mime), sink) { mediaCodecHandles }

    @Test fun bitstreamsTheSinkTakesAreLeftToMediaCodec() {
        assertTrue(yields(MimeTypes.AUDIO_AC3, sinkTaking(MimeTypes.AUDIO_AC3), mediaCodecHandles = false))
        assertTrue(yields(MimeTypes.AUDIO_DTS, sinkTaking(MimeTypes.AUDIO_DTS), mediaCodecHandles = false))
        assertTrue(yields(MimeTypes.AUDIO_TRUEHD, sinkTaking(MimeTypes.AUDIO_TRUEHD), mediaCodecHandles = false))
    }

    @Test fun bitstreamsTheReceiverCannotTakeStayWithFfmpegEvenWhereMediaCodecWouldDecodeThem() {
        val television = sinkTaking()
        assertFalse(yields(MimeTypes.AUDIO_AC3, television, mediaCodecHandles = true))
        assertFalse(yields(MimeTypes.AUDIO_E_AC3, television, mediaCodecHandles = true))
        assertFalse(yields(MimeTypes.AUDIO_DTS, television, mediaCodecHandles = true))
        assertFalse(yields(MimeTypes.AUDIO_DTS_HD, television, mediaCodecHandles = true))
        assertFalse(yields(MimeTypes.AUDIO_TRUEHD, television, mediaCodecHandles = true))
    }

    @Test fun aacAndMp3GoToMediaCodecOnceADecoderHandlesThem() {
        val television = sinkTaking()
        assertTrue(yields(MimeTypes.AUDIO_AAC, television, mediaCodecHandles = true))
        assertTrue(yields(MimeTypes.AUDIO_MPEG, television, mediaCodecHandles = true))
        assertFalse(yields(MimeTypes.AUDIO_AAC, television, mediaCodecHandles = false))
        assertFalse(yields(MimeTypes.AUDIO_MPEG, television, mediaCodecHandles = false))
    }

    @Test fun otherSoftwareDecodableFormatsStayWithFfmpeg() {
        val television = sinkTaking()
        assertFalse(yields(MimeTypes.AUDIO_OPUS, television, mediaCodecHandles = true))
        assertFalse(yields(MimeTypes.AUDIO_VORBIS, television, mediaCodecHandles = true))
        assertFalse(yields(MimeTypes.AUDIO_FLAC, television, mediaCodecHandles = true))
    }

    @Test fun removingTunnelingKeepsEveryOtherFlag() {
        val support = C.FORMAT_HANDLED or RendererCapabilities.ADAPTIVE_SEAMLESS or
            RendererCapabilities.TUNNELING_SUPPORTED or RendererCapabilities.AUDIO_OFFLOAD_SUPPORTED
        val untunneled = withoutTunneling(support)
        assertEquals(RendererCapabilities.TUNNELING_NOT_SUPPORTED, RendererCapabilities.getTunnelingSupport(untunneled))
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(untunneled))
        assertEquals(RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.getAdaptiveSupport(untunneled))
        assertEquals(RendererCapabilities.AUDIO_OFFLOAD_SUPPORTED, RendererCapabilities.getAudioOffloadSupport(untunneled))
    }

    @Test fun removingFormatSupportKeepsEveryOtherFlag() {
        val support = C.FORMAT_HANDLED or RendererCapabilities.ADAPTIVE_SEAMLESS or RendererCapabilities.TUNNELING_SUPPORTED
        val yielded = withoutFormatSupport(support)
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, RendererCapabilities.getFormatSupport(yielded))
        assertEquals(RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.getAdaptiveSupport(yielded))
        assertEquals(RendererCapabilities.TUNNELING_SUPPORTED, RendererCapabilities.getTunnelingSupport(yielded))
    }
}
