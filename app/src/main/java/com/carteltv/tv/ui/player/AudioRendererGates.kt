@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Whether the FFmpeg audio renderer, which is first in the list and so wins every tie, should report [format] as
 * unsupported so the MediaCodec renderer takes it instead:
 *  - the sink takes the bitstream as it is (receiver passthrough, or TrueHD carried as MAT): decoding it to PCM
 *    would lose the receiver's own Atmos and DTS:X processing;
 *  - AAC and MP3 that a MediaCodec decoder handles: same decode in hardware, and the renderer can still tunnel and offload.
 *
 * Everything else FFmpeg keeps: DTS and AC-3 where the receiver cannot take the bitstream get FFmpeg's downmix
 * rather than the vendor decoder's.
 */
internal fun ffmpegYieldsFormat(
    format: Format,
    audioSink: AudioSink,
    mediaCodecHandles: (Format) -> Boolean,
): Boolean {
    if (audioSink.getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY) return true
    return when (format.sampleMimeType) {
        MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 ->
            mediaCodecHandles(format)
        else -> false
    }
}

/** True when a MediaCodec decoder accepts [format] as playable and performant. */
fun mediaCodecHandlesAudio(context: Context, selector: MediaCodecSelector, format: Format): Boolean {
    val mime = format.sampleMimeType ?: return false
    val decoders = runCatching { selector.getDecoderInfos(mime, false, false) }
        .getOrDefault(emptyList())
    return decoders.any { runCatching { it.isFormatSupported(context, format) }.getOrDefault(false) }
}

fun ffmpegYieldingRenderer(
    renderer: Renderer,
    audioSink: AudioSink,
    mediaCodecHandles: (Format) -> Boolean,
): Renderer = withAdjustedSupport(renderer) { format, support ->
    if (RendererCapabilities.getFormatSupport(support) == C.FORMAT_HANDLED &&
        ffmpegYieldsFormat(format, audioSink, mediaCodecHandles)
    ) {
        withoutFormatSupport(support)
    } else {
        support
    }
}

/** [support] with its format support replaced by "unsupported subtype"; every other flag is kept. */
internal fun withoutFormatSupport(support: Int): Int =
    (support and RendererCapabilities.FORMAT_SUPPORT_MASK.inv()) or C.FORMAT_UNSUPPORTED_SUBTYPE

/**
 * The track selector tunnels only when the audio renderer calls the selected format tunnelable. A MAT bitstream
 * cannot be the tunnel master, so TrueHD is reported as not tunnelable and the video renders untunneled.
 */
fun withoutTunnelingForMime(renderer: Renderer, mime: String): Renderer = withAdjustedSupport(renderer) { format, support ->
    if (format.sampleMimeType == mime) withoutTunneling(support) else support
}

/** [support] with tunneling reported as unsupported; every other flag is kept. */
internal fun withoutTunneling(support: Int): Int = support and RendererCapabilities.TUNNELING_SUPPORT_MASK.inv()

/** Wraps [renderer] so [adjust] can rewrite the support level it reports for a format. Every other flag is kept. */
private fun withAdjustedSupport(renderer: Renderer, adjust: (Format, Int) -> Int): Renderer {
    val capabilities = object : RendererCapabilities by renderer.capabilities {
        override fun supportsFormat(format: Format): Int = adjust(format, renderer.capabilities.supportsFormat(format))
    }
    return object : ForwardingRenderer(renderer) {
        override fun getCapabilities(): RendererCapabilities = capabilities
    }
}
