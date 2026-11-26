@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.engine.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Per-session playback quality counters, logged under `adb logcat -s PLAYBACK_STATS`.
 * A stall is a return to BUFFERING after the first READY; the initial load is time-to-first-frame instead.
 */
class PlaybackStatistics(
    private val label: String,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : AnalyticsListener {
    private val startedAt = clock()
    private var wasReady = false
    private var stallStartedAt = 0L
    private var firstFrameMs = -1L

    var stalls = 0
        private set
    var stallMs = 0L
        private set
    var droppedFrames = 0L
        private set
    var errors = 0
        private set
    var videoFormat: String? = null
        private set
    var audioFormat: String? = null
        private set
    var bandwidthBps = 0L
        private set
    var measuredKbps = 0L
        private set
    var frameRate = 0f
        private set
    private val throughput = ThroughputSampler()

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (onState(state, clock())) Log.i(TAG, "[$label] stall ended, total ${stalls}x ${stallMs} ms")
    }

    /** Returns true when this transition ended a stall. */
    internal fun onState(state: Int, now: Long): Boolean {
        when (state) {
            Player.STATE_READY -> {
                wasReady = true
                if (stallStartedAt != 0L) {
                    stallMs += now - stallStartedAt
                    stallStartedAt = 0L
                    return true
                }
            }
            Player.STATE_BUFFERING -> if (wasReady && stallStartedAt == 0L) {
                stalls++
                stallStartedAt = now
            }
        }
        return false
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (firstFrameMs >= 0) return
        firstFrameMs = clock() - startedAt
        Log.i(TAG, "[$label] first frame after $firstFrameMs ms")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        videoFormat = describe(format)
        if (format.frameRate > 0f) frameRate = format.frameRate
        Log.i(TAG, "[$label] video $videoFormat")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        audioFormat = describe(format)
        Log.i(TAG, "[$label] audio $audioFormat")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    // Media3 1.11.1 has no per-frame processing-offset event; the cumulative counters arrive on
    // onVideoDisabled, which is where the final fx cost is sampled from.
    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
        decoderCounters.ensureUpdated()
        recordProcessingOffset(decoderCounters.totalVideoFrameProcessingOffsetUs, decoderCounters.videoFrameProcessingOffsetCount, clock())
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        bandwidthBps = bitrateEstimate
        recordThroughput(totalBytesLoaded, clock())
    }

    internal fun recordThroughput(totalBytesLoaded: Long, nowMs: Long) {
        throughput.sample(totalBytesLoaded, nowMs)?.let { measuredKbps = it / 1000 }
    }

    /** GL effects-pipeline telemetry: per-frame processing offset, p95 over a 10 s window. */
    private class FxSample(val atMs: Long, val perFrameUs: Long, val frames: Int)

    private var fxLastTotalUs = -1L
    private var fxLastFrames = -1
    private val fxSamples = ArrayDeque<FxSample>()

    internal fun recordProcessingOffset(totalUs: Long, frames: Int, nowMs: Long) {
        if (frames <= 0) return
        if (fxLastFrames < 0 || totalUs < fxLastTotalUs) {
            // First callback, or the counter reset because a new pipeline took over.
            fxLastTotalUs = totalUs
            fxLastFrames = frames
            return
        }
        val dTotal = totalUs - fxLastTotalUs
        val dFrames = frames - fxLastFrames
        fxLastTotalUs = totalUs
        fxLastFrames = frames
        if (dFrames <= 0) return
        fxSamples.addLast(FxSample(nowMs, dTotal / dFrames, dFrames))
        while (fxSamples.size > 1 && nowMs - fxSamples.first().atMs > FX_WINDOW_MS) fxSamples.removeFirst()
    }

    /** p95 per-frame GL processing offset over the window in us, or -1 with no samples. */
    fun fxP95Us(): Long {
        if (fxSamples.isEmpty()) return -1L
        val flat = ArrayList<Long>()
        for (s in fxSamples) repeat(s.frames) { flat.add(s.perFrameUs) }
        flat.sort()
        return flat[((flat.size - 1) * 95) / 100]
    }

    /** True when GL processing ate over 75% of the frame budget across a sustained window. */
    fun enhanceOverBudget(): Boolean {
        if (fxSamples.isEmpty() || clock() - fxSamples.first().atMs < FX_SUSTAIN_MS) return false
        val budgetUs = if (frameRate > 0f) (1_000_000f / frameRate).toLong() else DEFAULT_FRAME_BUDGET_US
        return fxP95Us() > budgetUs * 3 / 4
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        errors++
        Log.w(TAG, "[$label] error ${error.errorCodeName}")
    }

    fun summary(): String =
        "first_frame_ms=$firstFrameMs stalls=$stalls stall_ms=$stallMs dropped=$droppedFrames errors=$errors " +
            "fx_p95_us=${fxP95Us()} " +
            "bw_kbps=${bandwidthBps / 1000} measured_kbps=$measuredKbps video=${videoFormat ?: "-"} audio=${audioFormat ?: "-"}"

    fun logSummary() = Log.i(TAG, "[$label] ${summary()}")

    private fun describe(f: Format): String = buildString {
        append(f.sampleMimeType ?: "?")
        if (f.width > 0) append(' ').append(f.width).append('x').append(f.height)
        if (f.frameRate > 0f) append('@').append(f.frameRate)
        if (f.bitrate > 0) append(' ').append(f.bitrate / 1000).append("kbps")
        if (f.channelCount > 0) append(' ').append(f.channelCount).append("ch")
    }

    private companion object {
        const val TAG = "PLAYBACK_STATS"
        const val FX_WINDOW_MS = 10_000L
        const val FX_SUSTAIN_MS = 4_000L
        const val DEFAULT_FRAME_BUDGET_US = 41_666L
    }
}
