@file:androidx.media3.common.util.UnstableApi

/*
 *  The routing AudioSink wrapper.
 *
 *  Extends ForwardingAudioSink (delegates every AudioSink method by default) and overrides
 *  only the methods that must differ when routing TrueHD through the app-side MAT path.
 *  Non-TrueHD playback is byte-for-byte the delegate's behaviour; with the MAT toggle off
 *  this class is never constructed.
 *
 *  When active (input is TrueHD, IEC61937 sink opens):
 *    handleBuffer bytes -> TrueHdAudioFramer -> MatPacker -> preamble stamp
 *      -> ByteBuffer queue -> Iec61937MatSink (NON-BLOCKING writes)
 *
 *  Contract notes (against media3 DefaultAudioSink / AudioSink javadoc,
 *  and Kodi AESinkAUDIOTRACK):
 *   - NON-BLOCKING writes + a pending-frame queue. handleBuffer drains the queue first; if
 *     frames remain it returns false WITHOUT advancing the input buffer (AudioSink contract:
 *     the same ByteBuffer is re-offered until fully consumed), so the shared ExoPlayer
 *     playback thread never blocks in AudioTrack.write and the video renderer is not starved.
 *     The AudioTrack buffer is the real-time pacer.
 *   - The sink's track is created PAUSED and started on play(); flush() (seek) CLOSES and
 *     REOPENS it (DefaultAudioSink recreates on flush; head-across-flush is device-murky and
 *     our position is head-anchored) and restores the play state.
 *   - setVolume is a no-op in MAT mode (gain on an IEC61937 bitstream corrupts it).
 *
 *  Selection: the sink claims TrueHD (getFormatSupport) whenever the preference is on and the IEC61937 track
 *  opens, so the renderer hands the bitstream over even where the box has no TrueHD passthrough of its own.
 *
 *  KNOWN v1 LIMITATIONS (safe for single-stream movie playback):
 *   - No IEC pause-bursts are emitted on a genuine rebuffer; the receiver may re-hunt after a
 *     real network stall (Kodi's Android sink does not send them either).
 *   - onPositionDiscontinuity is raised only after a write failure reopened the track, not on
 *     a PTS jump (MatPacker handles timing).
 *   - Volume and audio effects set on the sink never reach the IEC track (gain would corrupt the bitstream).
 */
package com.carteltv.tv.engine.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.carteltv.tv.data.preferences.AppPreferences
import java.nio.ByteBuffer
import java.util.ArrayDeque

class MatRoutingAudioSink(
    delegate: AudioSink
) : ForwardingAudioSink(delegate) {

    private val framer = TrueHdAudioFramer()
    private var packer = MatPacker()
    private val matSink = Iec61937MatSink()

    // MAT frames stamped and awaiting write to the AudioTrack (our real-time buffer).
    private val pendingFrames = ArrayDeque<ByteBuffer>()

    @Volatile
    private var matMode = false
    private var isPlaying = false

    // Position anchoring: PTS of the first buffer handled since the last flush.
    private var startPtsUs = C.TIME_UNSET

    // Last MAT-mode configure() arguments: the delegate was never configured for this input,
    // so a failed reopen in flush() needs them to hand the stream to the delegate.
    private var matConfig: AudioSink.AudioSinkConfig? = null

    // Windowed average of consumed TrueHD input bytes, read by
    // diagnostics while MAT is active. Null until the first ~2 s window.
    private var rateWindowStartMs = 0L
    private var rateWindowBytes = 0L
    @Volatile
    private var lastInputRateBps = -1L
    private var playToEndCalled = false

    // The session every track of this player is asked to join (player.setAudioSessionId hands it to the sink).
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    // Tunneling requested by the renderer. An IEC bitstream cannot be the tunnel master, so MAT stays off while it is set.
    private var tunneling = false
    private var listener: AudioSink.Listener? = null
    // Set once a failed write has been answered with a reopen; a second failure is reported instead of retried.
    private var writeRecoveryUsed = false

    // Diagnostics, throttled to about once a second at debug level.
    private var dbgLastLogMs = 0L
    private var dbgAusTotal = 0L
    private var dbgFramesEnq = 0L
    private var dbgFramesDrained = 0L
    private var dbgBytesWrittenTotal = 0L
    private var dbgWriteErrors = 0L
    private var dbgLastWrite = 0
    private var dbgCallsSinceLog = 0

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        // Applied whenever the IEC track is (re)opened.
        this.audioSessionId = audioSessionId
        super.setAudioSessionId(audioSessionId)
    }

    override fun supportsFormat(format: Format): Boolean = matWouldTake(format) || super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (matWouldTake(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else super.getFormatSupport(format)

    /**
     * Whether this sink will carry [format] as a MAT bitstream. Opening the IEC61937 track is the only way to know
     * the box accepts it; the track is created paused, and [configure] reopens it before the first burst.
     */
    private fun matWouldTake(format: Format): Boolean =
        wantsMat(format) && (matSink.isOpen || matSink.open(audioSessionId))

    private fun wantsMat(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_TRUEHD &&
            AppPreferences.matTrueHd &&
            !tunneling &&
            android.os.SystemClock.elapsedRealtime() >= matSuspendedUntilMs

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val inputFormat = audioSinkConfig.format
        val wantMat = wantsMat(inputFormat)

        // Always a fresh track: a reconfigure mid-stream must not leave the previous stream's bursts queued.
        val newMatMode = if (wantMat) {
            matSink.close()
            if (matSink.open(audioSessionId)) {
                true
            } else {
                Log.w(TAG, "configure: MAT wanted but IEC61937 sink would not open; falling back")
                false
            }
        } else {
            false
        }

        if (matMode && !newMatMode) {
            matSink.close()
        }
        if (!matMode && newMatMode) {
            try { super.flush() } catch (_: Throwable) {} // bughunt:ignore kt-empty-catch
        }
        // A capability probe may have opened the track for a stream that then went elsewhere.
        if (!newMatMode && matSink.isOpen) matSink.close()

        matMode = newMatMode

        if (matMode) {
            matConfig = audioSinkConfig
            framer.reset()
            packer = MatPacker()
            pendingFrames.clear()
            startPtsUs = C.TIME_UNSET
            playToEndCalled = false
            writeRecoveryUsed = false
            resetDiag()
            resetInputRate()
            // A reconfigure may arrive on an already-playing sink; restore play state.
            if (isPlaying) matSink.play()
            listener?.onAudioSessionIdChanged(matSink.audioSessionId())
            Log.i(TAG, "configure: MAT mode ON  mime=${inputFormat.sampleMimeType}" +
                " pcmEnc=${inputFormat.pcmEncoding} rate=${inputFormat.sampleRate}" +
                " ch=${inputFormat.channelCount}")
        } else {
            super.configure(audioSinkConfig)
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!matMode) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (startPtsUs == C.TIME_UNSET) {
            startPtsUs = presentationTimeUs
        }

        // Drain any backlog first. If frames remain, apply back-pressure: return false
        // WITHOUT advancing the input buffer (AudioSink contract - media3 re-offers the
        // same buffer). This never blocks the shared playback thread.
        drainPending()
        if (pendingFrames.isNotEmpty()) {
            maybeLogDiag(buffer.remaining(), backlogged = true)
            return false
        }

        val len = buffer.remaining()
        val arr: ByteArray
        val off: Int
        if (buffer.hasArray()) {
            arr = buffer.array()
            off = buffer.arrayOffset() + buffer.position()
        } else {
            arr = ByteArray(len)
            val mark = buffer.position()
            buffer.get(arr)
            buffer.position(mark)
            off = 0
        }

        val ausThisCall = framer.feed(arr, off, len) { au ->
            if (packer.packTrueHD(au, au.size)) {
                var frame = packer.getOutputFrame()
                while (frame != null) {
                    // Payload words to wire byte order, THEN the
                    // LE preamble stamp over bytes 0..7.
                    Iec61937MatSink.swapPayloadWords(frame)
                    Iec61937MatSink.writeIecPreamble(frame)
                    pendingFrames.addLast(ByteBuffer.wrap(frame))
                    dbgFramesEnq += 1
                    frame = packer.getOutputFrame()
                }
            }
        }
        dbgAusTotal += ausThisCall

        // Best effort: drain what we just enqueued; leftovers ride the next call.
        drainPending()

        buffer.position(buffer.position() + len)
        val rateNowMs = android.os.SystemClock.elapsedRealtime()
        if (rateWindowStartMs == 0L) rateWindowStartMs = rateNowMs
        rateWindowBytes += len
        val rateWinMs = rateNowMs - rateWindowStartMs
        if (rateWinMs >= 2000L) {
            lastInputRateBps = rateWindowBytes * 8000L / rateWinMs
            rateWindowStartMs = rateNowMs
            rateWindowBytes = 0L
        }
        maybeLogDiag(len, backlogged = false)
        return true
    }

    private fun resetInputRate() {
        rateWindowStartMs = 0L
        rateWindowBytes = 0L
        lastInputRateBps = -1L
    }

    /** Drain as many queued frames as the AudioTrack will accept, NON-BLOCKING. */
    @Throws(AudioSink.WriteException::class)
    private fun drainPending() {
        while (pendingFrames.isNotEmpty()) {
            val head = pendingFrames.peekFirst() ?: break
            val n = matSink.writeNonBlocking(head)
            dbgLastWrite = n
            if (n < 0) {
                dbgWriteErrors += 1
                // The track died (audio server restart, HDMI sink change). One reopen, then report it: dropping the
                // burst would leave a frozen clock and silence with nothing to show for it.
                if (reopenAfterWriteError()) continue
                matSuspendedUntilMs = android.os.SystemClock.elapsedRealtime() + MAT_SUSPEND_MS
                val format = matConfig?.format ?: Format.Builder().build()
                Log.w(TAG, "MAT write failed again after a reopen ($n); giving MAT up until +${MAT_SUSPEND_MS}ms")
                throw AudioSink.WriteException(n, format, /* isRecoverable= */ true)
            }
            writeRecoveryUsed = false
            if (n > 0) dbgBytesWrittenTotal += n
            if (!head.hasRemaining()) {
                pendingFrames.pollFirst()
                dbgFramesDrained += 1
            } else {
                // Partial write - the AudioTrack buffer is full. Back-pressure.
                break
            }
        }
    }

    /** Reopens the track after a failed write. False when this failure was already answered with one. */
    private fun reopenAfterWriteError(): Boolean {
        if (writeRecoveryUsed) return false
        writeRecoveryUsed = true
        val resumePtsUs = getCurrentPositionUs(/* sourceEnded= */ false)
        Log.w(TAG, "MAT write returned $dbgLastWrite; reopening the IEC61937 track")
        matSink.close()
        if (!matSink.open(audioSessionId)) return false
        if (isPlaying) matSink.play()
        // The new track counts from zero, so it is anchored where the old one stopped.
        if (resumePtsUs != AudioSink.CURRENT_POSITION_NOT_SET) startPtsUs = resumePtsUs
        listener?.onPositionDiscontinuity()
        return true
    }

    override fun playToEndOfStream() {
        if (!matMode) {
            super.playToEndOfStream()
            return
        }
        // Called repeatedly at EOS by the renderer; each call drains more as the track plays out.
        drainPending()
        playToEndCalled = true
    }

    override fun isEnded(): Boolean {
        if (!matMode) return super.isEnded()
        return playToEndCalled && !hasPendingMatData()
    }

    override fun hasPendingData(): Boolean {
        if (!matMode) return super.hasPendingData()
        return hasPendingMatData()
    }

    private fun hasPendingMatData(): Boolean {
        if (pendingFrames.isNotEmpty()) return true
        val writtenFrames = matSink.bytesWritten() / Iec61937MatSink.IEC_FRAME_BYTES
        if (writtenFrames == 0L) return false
        val head = unwrappedPlaybackHead(writtenFrames)
        if (head < 0) return false
        return head < writtenFrames
    }

    private fun unwrappedPlaybackHead(writtenFrames: Long): Long {
        val head = matSink.playbackHeadPosition()
        return if (head < 0) -1L else Iec61937MatSink.unwrappedHead(head, writtenFrames)
    }

    override fun handleDiscontinuity() {
        if (!matMode) {
            super.handleDiscontinuity()
            return
        }
        // MatPacker detects seamless-branch/timing discontinuities from the AU headers.
    }

    override fun play() {
        // media3 contract: play() may arrive BEFORE configure() (a renderer enabled on
        // an already-playing player starts first, then configures - observed on-device
        // via a quiesce/re-enable cycle). DefaultAudioSink
        // latches a playing flag unconditionally and starts its track later; mirror
        // that: latch isPlaying regardless of matMode so the configure()/flush()
        // restore paths can start the MAT track, and always forward to the delegate so
        // its own play-state machine stays truthful for a later MAT disengage.
        isPlaying = true
        super.play()
        if (matMode) matSink.play()
    }

    override fun pause() {
        isPlaying = false
        super.pause()
        if (matMode) matSink.pause()
    }

    override fun flush() {
        if (matMode) {
            // Head-across-flush is device-murky and our position is head-anchored, so recreate
            // the track (as DefaultAudioSink does) rather than flush in place; restore play state.
            matSink.close()
            pendingFrames.clear()
            framer.reset()
            packer = MatPacker()
            startPtsUs = C.TIME_UNSET
            playToEndCalled = false
            writeRecoveryUsed = false
            resetDiag()
            resetInputRate()
            if (matSink.open(audioSessionId)) {
                if (isPlaying) matSink.play()
            } else {
                // Same fallback as configure(): MAT mode with no track would drop every frame
                // and freeze the position, so hand the stream to the delegate sink.
                Log.w(TAG, "flush: IEC61937 sink would not reopen; falling back")
                matMode = false
                super.flush()
                try {
                    matConfig?.let { super.configure(it) }
                } catch (e: AudioSink.ConfigurationException) {
                    // flush() cannot throw it; the unconfigured delegate then fails the next
                    // handleBuffer as a visible playback error rather than a silent stall.
                    Log.w(TAG, "flush: delegate rejected the fallback configure", e)
                }
            }
        } else {
            super.flush()
        }
    }

    override fun reset() {
        matSink.close()
        pendingFrames.clear()
        framer.reset()
        packer = MatPacker()
        matMode = false
        isPlaying = false
        tunneling = false
        writeRecoveryUsed = false
        matConfig = null
        startPtsUs = C.TIME_UNSET
        playToEndCalled = false
        resetInputRate()
        super.reset()
    }

    override fun release() {
        matSink.close()
        pendingFrames.clear()
        super.release()
    }

    override fun setVolume(volume: Float) {
        if (matMode) {
            // Gain on an IEC61937 bitstream corrupts it; volume is a no-op in MAT mode.
            if (volume != 1.0f) Log.i(TAG, "setVolume($volume) ignored in MAT mode")
        } else {
            super.setVolume(volume)
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!matMode) return super.getCurrentPositionUs(sourceEnded)
        if (startPtsUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val writtenFrames = matSink.bytesWritten() / Iec61937MatSink.IEC_FRAME_BYTES
        val head = unwrappedPlaybackHead(writtenFrames)
        if (head < 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val playedUs = head * 1_000_000L / CARRIER_FRAMES_PER_SECOND
        return startPtsUs + playedUs
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        if (!matMode) return super.getAudioTrackBufferSizeUs()
        val frames = matSink.bufferSizeFrames()
        if (frames <= 0) return DEFAULT_BUFFER_US
        return frames.toLong() * 1_000_000L / CARRIER_FRAMES_PER_SECOND
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // MAT is bitstream passthrough: only 1.0 speed is achievable. Forward so the delegate
        // stores the value (harmless while dormant); the MAT sink always plays at native rate.
        super.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        if (matMode) return PlaybackParameters.DEFAULT
        return super.getPlaybackParameters()
    }

    override fun enableTunnelingV21() {
        tunneling = true
        if (!matMode) super.enableTunnelingV21()
    }

    override fun disableTunneling() {
        tunneling = false
        super.disableTunneling()
    }

    private fun resetDiag() {
        dbgLastLogMs = 0L; dbgAusTotal = 0L; dbgFramesEnq = 0L; dbgFramesDrained = 0L
        dbgBytesWrittenTotal = 0L; dbgWriteErrors = 0L; dbgLastWrite = 0; dbgCallsSinceLog = 0
    }

    private fun maybeLogDiag(len: Int, backlogged: Boolean) {
        dbgCallsSinceLog += 1
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - dbgLastLogMs < 1000L) return
        dbgLastLogMs = nowMs
        Log.d(TAG, "mat-diag: calls=$dbgCallsSinceLog len=$len backlog=$backlogged" +
            " pending=${pendingFrames.size} head=${matSink.playbackHeadPosition()}" +
            " playState=${matSink.playState()} underruns=${matSink.underrunCount()}" +
            " AUtot=$dbgAusTotal enq=$dbgFramesEnq drained=$dbgFramesDrained" +
            " wrtot=$dbgBytesWrittenTotal wrErr=$dbgWriteErrors lastWrite=$dbgLastWrite")
        dbgCallsSinceLog = 0
    }

    companion object {
        private const val TAG = "MatRoutingAudioSink"
        // 192000 carrier frames == 1 s of media (50 MAT frames/s * 3840 carrier frames each).
        private const val CARRIER_FRAMES_PER_SECOND = 192_000L
        private const val DEFAULT_BUFFER_US = 250_000L
        // After a write failure that outlived a reopen, let TrueHD play through software decode for a while
        // instead of failing the same way on every retry.
        private const val MAT_SUSPEND_MS = 60_000L
        @Volatile
        private var matSuspendedUntilMs = 0L
    }
}
