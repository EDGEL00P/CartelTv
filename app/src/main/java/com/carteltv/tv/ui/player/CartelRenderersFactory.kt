package com.carteltv.tv.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.engine.audio.MatRoutingAudioSink
import com.carteltv.tv.engine.captions.RenderTimeSubtitles

/**
 * Extension renderers are constructed explicitly instead of relying on DefaultRenderersFactory's
 * reflection, so the classpath alone decides nothing and order is exact: FFmpeg audio first, dav1d
 * right after MediaCodec video.
 *
 * FFmpeg audio (lib-decoder-ffmpeg AAR): TrueHD / DTS / DTS-HD / AC-3 / E-AC-3 decode in-app when
 * the receiver can't take the bitstream, with a Kodi AudioEngine-derived downmix (limiter, bass-
 * preserving center mix) instead of the vendor decoder's silent stereo fold. It is first, so it
 * would win every tie with the MediaCodec renderer; [ffmpegYieldingRenderer] makes it step aside
 * for bitstreams the sink passes through and for AAC/MP3 a MediaCodec decoder handles.
 *
 * dav1d (lib-decoder-av1 AAR): [SoftwareAv1Renderer] reports unsupported wherever hardware AV1
 * exists, so on the Shield hardware wins every tie and dav1d only rescues boxes without an
 * AV1 chip (8-bit, up to 1080p).
 */
@OptIn(UnstableApi::class)
class CartelRenderersFactory(
    context: Context,
    private val live: Boolean = false,
) : DefaultRenderersFactory(context) {

    init {
        // A decoder that fails to initialise (busy, profile or resolution limit) retries on the next one instead of ending playback.
        setEnableDecoderFallback(true)
    }

    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory =
        LowLatencyAdapterFactory(super.getCodecAdapterFactory(), AppPreferences.lowLatencyLive, live)

    @Suppress("DEPRECATION")
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        baseSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val matTrueHd = AppPreferences.matTrueHd
        val audioSink = if (matTrueHd) MatRoutingAudioSink(baseSink) else baseSink

        // Dialogue Boost needs decoded PCM, so with it on FFmpeg gets its own sink carrying the processor chain
        // and yields to nothing: every format it can decode has to reach the processor. Off keeps the shared sink.
        val boost = AppPreferences.dialogueBoost
        if (boost in 1..3) {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, DialogueBoostProcessor(boost)))
        } else {
            val ffmpeg = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
            out.add(
                ffmpegYieldingRenderer(ffmpeg, audioSink) { format ->
                    mediaCodecHandlesAudio(context, mediaCodecSelector, format)
                },
            )
        }

        val mediaCodecRenderers = ArrayList<Renderer>()
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            mediaCodecRenderers,
        )
        // TrueHD leaves as a MAT bitstream, which cannot be the tunnel master.
        mediaCodecRenderers.mapTo(out) { renderer ->
            if (matTrueHd) withoutTunnelingForMime(renderer, MimeTypes.AUDIO_TRUEHD) else renderer
        }
    }

    /**
     * Raw PGS/SRT/VobSub samples are deliberately left unparsed during extraction
     * (RenderTimeSubtitles), so the text renderer must be allowed to decode them itself.
     * Without this, the first raw application/x-subrip sample fails a renderer runtime
     * check and playback dies with ERROR_CODE_FAILED_RUNTIME_CHECK.
     */
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        RenderTimeSubtitles.enableRenderTimeDecoding(out)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            built,
        )
        if (dav1dLoaded()) {
            val slot = softwareAv1Slot(built) { it::class.java == MediaCodecVideoRenderer::class.java }
            built.add(
                slot,
                createSoftwareAv1Renderer(allowedVideoJoiningTimeMs, eventHandler, eventListener) { format ->
                    hardwareHandlesAv1(context, mediaCodecSelector, format)
                },
            )
        }
        out.addAll(built)
    }
}
