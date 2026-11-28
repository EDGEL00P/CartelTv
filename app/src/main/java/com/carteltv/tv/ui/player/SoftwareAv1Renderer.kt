@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.av1.Dav1dLibrary
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Decides when the dav1d software decoder may take an AV1 track. It exists to rescue streams hardware cannot
 * play, so it declines whenever a hardware decoder accepts the stream, and it only takes 8-bit video up to
 * 1080p, the most a software decoder can sustain on a set-top SoC.
 */
object SoftwareAv1Policy {
    private const val MAX_LONG_SIDE = 1920
    private const val MAX_SHORT_SIDE = 1088

    /** [hardwareHandles] is queried only for a stream inside the software limits. */
    fun formatSupport(format: Format, decoderSupport: Int, hardwareHandles: () -> Boolean): Int {
        if (decoderSupport != C.FORMAT_HANDLED) return decoderSupport
        if (isHighBitDepth(format)) return C.FORMAT_UNSUPPORTED_SUBTYPE
        // Over the size dav1d can sustain it declines whether or not hardware exists: "exceeds capabilities" is
        // still selected by default, and would hand the track to a decoder that cannot keep up.
        if (exceedsSoftwareSize(format) || hardwareHandles()) return C.FORMAT_UNSUPPORTED_SUBTYPE
        return C.FORMAT_HANDLED
    }

    /** False for a stream with no declared size: there is nothing to judge, and containers state video size. */
    fun exceedsSoftwareSize(format: Format): Boolean {
        val w = format.width
        val h = format.height
        if (w == Format.NO_VALUE || h == Format.NO_VALUE) return false
        return maxOf(w, h) > MAX_LONG_SIDE || minOf(w, h) > MAX_SHORT_SIDE
    }

    /** True for 10/12-bit streams, and for HDR transfers when the bit depth is not declared. */
    fun isHighBitDepth(format: Format): Boolean {
        val depth = bitDepth(format)
        if (depth != null) return depth > 8
        return when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG -> true
            else -> false
        }
    }

    /** Bit depth from ColorInfo, the `av01.P.LLT.DD` codec string, or the av1C box, whichever is present first. */
    fun bitDepth(format: Format): Int? {
        format.colorInfo?.lumaBitdepth?.takeIf { it > 0 }?.let { return it }
        format.codecs?.split(',')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("av01.") }
            ?.split('.')
            ?.getOrNull(3)
            ?.toIntOrNull()
            ?.let { return it }
        val av1c = format.initializationData.firstOrNull() ?: return null
        val markerAndVersion = av1c.getOrNull(0)?.toInt() ?: return null
        if (markerAndVersion and 0x80 == 0) return null
        val flags = av1c.getOrNull(2)?.toInt() ?: return null
        val highBitDepth = flags and 0x40 != 0
        val twelveBit = flags and 0x20 != 0
        return when {
            !highBitDepth -> 8
            twelveBit -> 12
            else -> 10
        }
    }
}

/** dav1d is placed right after MediaCodec so hardware wins ties; with no MediaCodec renderer it goes last. */
fun softwareAv1Slot(videoRenderers: List<Renderer>, isMediaCodecRenderer: (Renderer) -> Boolean): Int {
    val index = videoRenderers.indexOfFirst(isMediaCodecRenderer)
    return if (index < 0) videoRenderers.size else index + 1
}

fun dav1dLoaded(): Boolean = Dav1dLibrary.isAvailable()

/** Whether one of the device's hardware AV1 decoders (not the platform's software one) plays [format] acceptably. */
fun hardwareHandlesAv1(context: Context, selector: MediaCodecSelector, format: Format): Boolean {
    val mime = format.sampleMimeType ?: return false
    val hardware = runCatching { selector.getDecoderInfos(mime, false, false) }
        .getOrDefault(emptyList())
        .filterNot { it.softwareOnly }
    return hardware.any { runCatching { it.isFormatSupported(context, format) }.getOrDefault(false) }
}

fun createSoftwareAv1Renderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    hardware: (Format) -> Boolean,
): Renderer = SoftwareAv1Renderer(
    Libdav1dVideoRenderer(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
    ),
    hardware,
)

/** Wraps the dav1d renderer so [SoftwareAv1Policy] decides which tracks it accepts. */
class SoftwareAv1Renderer(
    renderer: Renderer,
    hardware: (Format) -> Boolean,
) : ForwardingRenderer(renderer) {
    private val capabilities = GatedCapabilities(renderer.capabilities, hardware)

    override fun getCapabilities(): RendererCapabilities = capabilities
}

private class GatedCapabilities(
    private val delegate: RendererCapabilities,
    private val hardware: (Format) -> Boolean,
) : RendererCapabilities by delegate {

    override fun supportsFormat(format: Format): Int {
        val capabilities = delegate.supportsFormat(format)
        val decoderSupport = RendererCapabilities.getFormatSupport(capabilities)
        val support = SoftwareAv1Policy.formatSupport(format, decoderSupport) { hardware(format) }
        if (support == decoderSupport) return capabilities
        return RendererCapabilities.create(
            support,
            RendererCapabilities.getAdaptiveSupport(capabilities),
            RendererCapabilities.getTunnelingSupport(capabilities),
        )
    }
}
