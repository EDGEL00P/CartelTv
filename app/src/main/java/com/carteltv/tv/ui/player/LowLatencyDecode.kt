package com.carteltv.tv.ui.player

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/** Decides whether a codec instance is asked for Android 11's low-latency decode mode. */
object LowLatencyDecode {
    fun shouldRequest(
        enabled: Boolean,
        live: Boolean,
        sdk: Int,
        isVideo: Boolean,
        secure: Boolean,
        featureSupported: Boolean,
    ): Boolean = enabled && live && sdk >= Build.VERSION_CODES.R && isVideo && !secure && featureSupported
}

/**
 * Sets MediaFormat.KEY_LOW_LATENCY on live video decoders that advertise FEATURE_LowLatency, so the decoder
 * emits frames as soon as they are decoded instead of holding them back. Wrapping the adapter factory leaves
 * ExoPlayer's own video renderer, and every option its factory sets on it, untouched.
 */
@OptIn(UnstableApi::class)
class LowLatencyAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
    private val enabled: Boolean,
    private val live: Boolean,
) : MediaCodecAdapter.Factory {

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val supported = configuration.codecInfo.capabilities
            ?.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) == true
        val request = LowLatencyDecode.shouldRequest(
            enabled = enabled,
            live = live,
            sdk = Build.VERSION.SDK_INT,
            isVideo = MimeTypes.isVideo(configuration.format.sampleMimeType),
            secure = configuration.crypto != null,
            featureSupported = supported,
        )
        if (request) configuration.mediaFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        return delegate.createAdapter(configuration)
    }
}
