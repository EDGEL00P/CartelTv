@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.engine.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Build
import android.view.Display
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.carteltv.tv.engine.dolbyvision.DolbyVisionConversionConfig

/**
 * What this box and its current display can actually show.
 * A null set means the probe could not tell; callers must then keep their previous behaviour.
 */
internal data class VideoCaps(
    val displayHdrTypes: Set<Int>?,
    val dvDecoderProfiles: Set<Int>?,
) {
    val displayHasDolbyVision: Boolean
        get() = displayHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
    val decoderHasDvProfile7: Boolean
        get() = dvDecoderProfiles?.contains(CodecProfileLevel.DolbyVisionProfileDvheDtb) == true
    val decoderHasDvProfile5: Boolean
        get() = dvDecoderProfiles?.contains(CodecProfileLevel.DolbyVisionProfileDvheStn) == true
}

internal object DeviceVideoCapabilities {

    // Decoders do not change while the process lives; the display can (HDMI hotplug), so it is probed per call.
    private val decoderProfiles: Set<Int>? by lazy { probeDecoderProfiles() }

    fun probe(context: Context): VideoCaps = VideoCaps(probeDisplayHdrTypes(context), decoderProfiles)

    @Suppress("DEPRECATION")
    private fun probeDisplayHdrTypes(context: Context): Set<Int>? = runCatching {
        val display = context.applicationContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching null
        val types = if (Build.VERSION.SDK_INT >= 34) {
            display.mode.supportedHdrTypes
        } else {
            display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        }
        types.toSet()
    }.getOrNull()

    @OptIn(UnstableApi::class)
    private fun probeDecoderProfiles(): Set<Int>? = runCatching {
        MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, /* requiresSecureDecoder = */ false, /* requiresTunnelingDecoder = */ false)
            .asSequence()
            .filter { it.hardwareAccelerated }
            .flatMap { it.getProfileLevels().orEmpty().asSequence().map { level -> level.profile } }
            .toSet()
    }.getOrNull()
}

/**
 * Turns measured capability into the per-stream conversion config.
 * Conversion stays on whatever the display shows: a DV8.1 stream still plays as HDR10 on a non-DV panel,
 * which is the case the P7 to P8.1 rewrite exists for. Only two decisions follow from capability:
 * relabelling unlabelled Profile 5 needs a decoder that takes Profile 5, and native Profile 7 needs the
 * user's opt-in plus both a Profile 7 decoder and a Dolby Vision display.
 */
internal object DvPolicy {
    fun resolve(caps: VideoCaps, allowNativeProfile7: Boolean): DolbyVisionConversionConfig =
        DolbyVisionConversionConfig(
            active = true,
            nativeProfile7 = allowNativeProfile7 && caps.decoderHasDvProfile7 && caps.displayHasDolbyVision,
            relabelUnlabelledP5 = caps.decoderHasDvProfile5,
        )
}
