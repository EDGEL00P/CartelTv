@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.player

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.StalkerClient
import com.carteltv.tv.data.xtream.StreamMetadata
import com.carteltv.tv.engine.dolbyvision.DolbyVisionExtractorsFactory
import com.carteltv.tv.engine.playback.DeviceVideoCapabilities
import com.carteltv.tv.engine.playback.DrmPolicy
import com.carteltv.tv.engine.playback.DvPolicy
import com.carteltv.tv.engine.playback.LenientMediaSourceFactory
import com.carteltv.tv.engine.playback.SeekAccumulator
import com.carteltv.tv.engine.stability.PlaybackBufferPolicy

/** Media3's default estimate is a few Mbps, so multi-variant HLS starts on its lowest rendition and ramps slowly. */
internal const val INITIAL_BITRATE_BPS = 20_000_000L

/** IPTV transport streams often omit access-unit delimiters and IDR keyframes; without these flags they stall in buffering. */
internal const val TS_PAYLOAD_FLAGS =
    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES

/**
 * The one ExoPlayer recipe for every surface that shows video: the main player, YouTube and the
 * multi-view tiles. A host adds only what is its own (live retry, captions, its data source) on top
 * of the returned builder. Nothing set here is restated by a host, so renderers, tracks, buffering
 * and focus cannot drift apart between players.
 *
 * [sourceFactory] null means provider streams over [cartelDataSource]. [audioFocus] is off only where
 * several of these players play at once and one of them holds focus for the rest, as in multi-view.
 */
@OptIn(UnstableApi::class)
fun buildCartelPlayer(
    context: Context,
    live: Boolean,
    sourceFactory: MediaSource.Factory? = null,
    audioFocus: Boolean = true,
    deepBuffer: Boolean = false,
): ExoPlayer.Builder {
    val lowRam = isLowRamDevice(context)
    val spec =
        if (live) {
            PlaybackBufferPolicy.live(
                AppPreferences.bufferPreset,
                lowRam,
                deepBuffer,
            )
        } else {
            PlaybackBufferPolicy.vod(AppPreferences.bufferPreset, lowRam)
        }
    return ExoPlayer
        .Builder(context)
        .setRenderersFactory(CartelRenderersFactory(context, live))
        .setMediaSourceFactory(sourceFactory ?: LenientMediaSourceFactory(cartelDataSource(context), cartelExtractors(context)))
        .setLoadControl(
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(spec.minMs, spec.maxMs, spec.startMs, spec.rebufferStartMs)
                .apply { spec.targetBytes?.let { setTargetBufferBytes(it) } }
                .build(),
        ).setBandwidthMeter(
            DefaultBandwidthMeter
                .Builder(context)
                .setInitialBitrateEstimate(INITIAL_BITRATE_BPS)
                .build(),
        ).setTrackSelector(
            DefaultTrackSelector(context).also { selector ->
                // Let ABR switch between H.264/HEVC renditions as well as bitrates; sports variants are not
                // always packaged with one codec. Tunneling cannot run through the GL effects pipeline.
                val tunneling = AppPreferences.tunneling && !AppPreferences.videoEnhance
                val audioOffload = AppPreferences.audioOffload
                val builder =
                    selector
                        .buildUponParameters()
                        .setAllowVideoMixedMimeTypeAdaptiveness(true)
                        .setTunnelingEnabled(tunneling)
                if (audioOffload) {
                    builder.setAudioOffloadPreferences(
                        TrackSelectionParameters.AudioOffloadPreferences
                            .Builder()
                            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                            .build(),
                    )
                }
                selector.setParameters(builder)
            },
        )
        // Audio focus pauses other apps' audio while this player is up; on TV boxes it can also
        // freeze CEC/launcher preview playback when the player opens.
        .setAudioAttributes(AudioAttributes.DEFAULT, audioFocus)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        // seekBack()/seekForward() serve media sessions and remotes, so they step exactly like the deck's buttons.
        .setSeekBackIncrementMs(SeekAccumulator.BASE_STEP_MS)
        .setSeekForwardIncrementMs(SeekAccumulator.BASE_STEP_MS)
}

/** HTTP for provider streams: cross-protocol redirects and the app's standard timeouts. */
@OptIn(UnstableApi::class)
internal fun cartelHttpFactory(headers: Map<String, String> = emptyMap()): DefaultHttpDataSource.Factory =
    DefaultHttpDataSource
        .Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(12_000)
        .setReadTimeoutMs(30_000)
        .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }

/**
 * A provider stream over [http]. The resolver rewrites Stalker portal links, which carry the
 * real address inside the path. Live retry is the main player's to add (see LiveRetryDataSource).
 */
internal fun providerDataSource(
    context: Context,
    http: DataSource.Factory = cartelHttpFactory(),
): ResolvingDataSource.Factory = ResolvingDataSource.Factory(DefaultDataSource.Factory(context, http), StalkerClient.Resolver)

/** The app's extractor set: Dolby Vision handling and chapter reading on top of Media3's defaults. */
internal fun cartelExtractors(context: Context): ExtractorsFactory =
    DolbyVisionExtractorsFactory(
        delegate = DefaultExtractorsFactory().setTsExtractorFlags(TS_PAYLOAD_FLAGS),
        config = DvPolicy.resolve(DeviceVideoCapabilities.probe(context), AppPreferences.dvNativeProfile7),
    )

/** Provider streams over the shared HTTP factory: what [buildCartelPlayer] falls back to. */
internal fun cartelDataSource(
    context: Context,
    headers: Map<String, String> = emptyMap(),
): ResolvingDataSource.Factory = providerDataSource(context, cartelHttpFactory(headers))

/** A media item with the playlist-declared DRM when the stream carries any, a plain URI otherwise. */
internal fun cartelMediaItem(
    url: String,
    meta: StreamMetadata?,
): MediaItem {
    val drm = meta?.drm?.let { DrmPolicy.configuration(it, meta.headers) } ?: return MediaItem.fromUri(url)
    return MediaItem
        .Builder()
        .setUri(url)
        .setDrmConfiguration(drm)
        .build()
}

/**
 * A live channel item: [cartelMediaItem] plus the cushioning live offset from [PlaybackBufferPolicy],
 * so playback begins behind the edge instead of at it and falls back there after a stall. The speed
 * bounds are pinned at 1.0 deliberately: gradual reserve stays with LiveBufferCushion (which rides on
 * the per-title speed), and leaving Media3 free to adjust speed would put two owners on one knob.
 */
internal fun cartelLiveMediaItem(
    url: String,
    meta: StreamMetadata?,
): MediaItem =
    cartelMediaItem(url, meta)
        .buildUpon()
        .setLiveConfiguration(
            MediaItem.LiveConfiguration
                .Builder()
                .setTargetOffsetMs(PlaybackBufferPolicy.liveTargetOffsetMs(AppPreferences.bufferPreset))
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1f)
                .build(),
        ).build()

internal fun isLowRamDevice(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
