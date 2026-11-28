/*
 *  The IEC61937 output sink.
 *
 *  Owns an ENCODING_IEC61937 192 kHz / 8-channel / 16-bit AudioTrack and writes
 *  IEC61937-framed MAT bursts (produced upstream: MatPacker output with the preamble
 *  stamped in) into it. The AudioTrack buffer is the real-time pacer.
 *
 *  Design notes:
 *   - Writes are NON-BLOCKING (write(ByteBuffer, size, WRITE_NON_BLOCKING)), exactly as
 *     DefaultAudioSink does for every format. The caller manages partial writes via the
 *     ByteBuffer position and applies back-pressure. A blocking write here starved
 *     ExoPlayer's single playback thread (the video renderer shares it) - the stutter root.
 *   - The track is created PAUSED (Kodi does pause()+flush() at create; DefaultAudioSink
 *     never plays at configure). It starts only on play(), by which point frames are queued -
 *     continuous bursts from the first sample instead of an empty-track underrun.
 *   - A start threshold defers output until a stable initial burst is buffered.
 *
 *  Preamble bytes follow FFmpeg's spdif. Payload words are byte-swapped to wire order before write
 *  (see swapPayloadWords); that is invisible on zero-stuffed bursts, so test with real audio.
 *
 *  NOTE: playbackHeadPosition is an unsigned 32-bit frame counter (read via unsignedHead);
 *  at 192 kHz it wraps after ~6.2 h of playback without a flush/reopen.
 */
package com.carteltv.tv.engine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer

class Iec61937MatSink {

    private var track: AudioTrack? = null
    // Total bytes written since open/reopen; / IEC_FRAME_BYTES gives carrier frames written.
    private var bytesWritten: Long = 0L

    /** True once an AudioTrack is constructed (created paused, ready for writes). */
    val isOpen: Boolean get() = track?.state == AudioTrack.STATE_INITIALIZED

    /**
     * Construct the IEC61937 AudioTrack, created PAUSED. Returns false if the framework
     * rejects the configuration (caller should fall back to the normal sink path).
     *
     * @param audioSessionId Session the track should join when positive; otherwise the track gets a session of its own.
     */
    fun open(audioSessionId: Int = 0): Boolean {
        close()
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_IEC61937)
                .setSampleRate(IEC_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                IEC_RATE_HZ, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_IEC61937
            )
            if (minBuf <= 0) {
                Log.w(TAG, "open: getMinBufferSize=$minBuf, config rejected")
                return false
            }
            val attrs = movieAttributes()
            // ~1 s buffer; fall back to the old small size if the HAL rejects it.
            val bigBuf = maxOf(minBuf, MatPacker.MAT_BUFFER_SIZE * BUF_MAT_FRAMES_LARGE)
            val smallBuf = maxOf(minBuf, MatPacker.MAT_BUFFER_SIZE * 4)
            var built = buildTrack(format, attrs, bigBuf, audioSessionId)
            var reqBuf = bigBuf
            if (built == null) {
                Log.w(TAG, "open: large buffer $bigBuf rejected; retrying with $smallBuf")
                built = buildTrack(format, attrs, smallBuf, audioSessionId)
                reqBuf = smallBuf
            }
            val newTrack = built ?: run {
                Log.w(TAG, "open: AudioTrack not INITIALIZED on either buffer size")
                return false
            }
            // Begin output only once a stable initial burst is buffered.
            var thresh = -1
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try { thresh = newTrack.setStartThresholdInFrames(START_THRESHOLD_FRAMES) } catch (_: Throwable) {} // bughunt:ignore kt-empty-catch
            }
            // Create PAUSED (Kodi-style pause+flush); play() starts it once frames are queued.
            try { newTrack.pause(); newTrack.flush() } catch (_: Throwable) {} // bughunt:ignore kt-empty-catch
            track = newTrack
            bytesWritten = 0L
            Log.i(TAG, "open: IEC61937 192k/8ch created PAUSED, reqBuf=$reqBuf" +
                " actualFrames=${newTrack.bufferSizeInFrames} startThresh=$thresh")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "open threw ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun buildTrack(format: AudioFormat, attrs: AudioAttributes, bufBytes: Int, audioSessionId: Int): AudioTrack? {
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply { if (audioSessionId > 0) setSessionId(audioSessionId) }
                .build()
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                t
            } else {
                Log.w(TAG, "buildTrack: not INITIALIZED (state=${t.state}, buf=$bufBytes)")
                t.release()
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "buildTrack threw ${e.javaClass.simpleName} (buf=$bufBytes)")
            null
        }
    }

    /**
     * Write from [buffer] (position..limit) to the AudioTrack, NON-BLOCKING. Returns bytes
     * written (advancing the buffer position), 0 if the track buffer is full, or a negative
     * AudioTrack error / -1 if not open. Caller re-offers a partially-written buffer.
     */
    fun writeNonBlocking(buffer: ByteBuffer): Int {
        val t = track ?: return -1
        return try {
            val n = t.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (n > 0) bytesWritten += n
            n
        } catch (e: Throwable) {
            Log.w(TAG, "writeNonBlocking threw ${e.javaClass.simpleName}")
            -1
        }
    }

    /** Playback head in carrier frames (192 kHz), or -1 if not open. */
    fun playbackHeadPosition(): Long {
        val t = track ?: return -1L
        return try { unsignedHead(t.playbackHeadPosition) } catch (_: Throwable) { -1L }
    }

    /** Total bytes written since open/reopen. */
    fun bytesWritten(): Long = bytesWritten

    /** AudioTrack play state (PLAYSTATE_*), or -1. */
    fun playState(): Int = try { track?.playState ?: -1 } catch (_: Throwable) { -1 }

    /** Native AudioTrack underrun count (API 24+), or -1. */
    fun underrunCount(): Int = try { track?.underrunCount ?: -1 } catch (_: Throwable) { -1 }

    /** Actual allocated buffer size in frames, or 0. */
    fun bufferSizeFrames(): Int = try { track?.bufferSizeInFrames ?: 0 } catch (_: Throwable) { 0 }

    /** Session the track belongs to, or 0 while no track is open. */
    fun audioSessionId(): Int = try { track?.audioSessionId ?: 0 } catch (_: Throwable) { 0 }

    fun pause() { try { track?.pause() } catch (_: Throwable) {} // bughunt:ignore kt-empty-catch
    }

    /** Start or resume playback (also the resume-after-pause path). */
    fun play() { try { track?.play() } catch (_: Throwable) {} // bughunt:ignore kt-empty-catch
    }

    fun close() {
        val t = track ?: return
        try { t.pause(); t.flush(); t.stop() } catch (_: Throwable) {} // bughunt:ignore kt-empty-catch
        try { t.release() } catch (_: Throwable) {} // bughunt:ignore kt-empty-catch
        track = null
        bytesWritten = 0L
    }

    companion object {
        private const val TAG = "Iec61937MatSink"
        private const val IEC_RATE_HZ = 192_000
        // 8-channel 16-bit IEC61937 frame = 16 bytes; ~1 s buffer = 50 MAT frames.
        const val IEC_FRAME_BYTES = 16
        private const val BUF_MAT_FRAMES_LARGE = 50
        // Defer output until ~8 MAT frames (8 * 3840 = 30720 carrier frames ~= 160 ms) buffered.
        private const val START_THRESHOLD_FRAMES = 30720

        // Empirically-validated IEC61937 preamble for MAT-wrapped TrueHD :
        // Pa=0xF872, Pb=0x4E1F, Pc=IEC61937_TRUEHD (0x16), Pd=MAT_FRAME_SIZE (61424 BYTES).
        private const val PA = 0xF872
        private const val PB = 0x4E1F
        private const val PC_TRUEHD = 0x0016
        private const val PD_TRUEHD = 61424

        /**
         * AudioTrack.getPlaybackHeadPosition is an unsigned 32-bit counter returned in a
         * signed Int: past 2^31 frames (~3.1 h at 192 kHz) it reads negative.
         */
        internal fun unsignedHead(rawHead: Int): Long = rawHead.toLong() and 0xFFFFFFFFL

        /**
         * The 32-bit playback head as a continuous frame count. [writtenFrames] is unbounded and never behind the
         * head, so it supplies the high bits: past 2^32 frames (about 6.2 h) the head reads as having wrapped.
         */
        internal fun unwrappedHead(head: Long, writtenFrames: Long): Long {
            val wrap = 1L shl 32
            var full = (writtenFrames / wrap) * wrap + head
            if (full > writtenFrames) full -= wrap
            return full.coerceAtLeast(0L)
        }

        /**
         * Pairwise 16-bit byte swap of the whole MAT frame, mirroring Kodi's
         * CAEPackIEC61937::PackTrueHD on little-endian systems (SwapEndian over the
         * payload, AEPackIEC61937.cpp): the IEC61937 payload's 16-bit words travel
         * big-endian on the wire while the four preamble words are little-endian.
         * MatPacker leaves bytes 0..7 zeroed, so swapping the ENTIRE frame and then
         * stamping the preamble (writeIecPreamble overwrites 0..7) is equivalent to
         * Kodi's payload-only swap. Call BEFORE writeIecPreamble. Why every earlier
         * probe missed this: the HAL's format detector and the head-advance
         * cells read only the preamble, and zero-stuffed burst payloads are
         * endianness-invariant - the claim was untestable until the Kodi control
         * (locks the receiver on this box) was run against verbatim payload (no
         * lock).
         */
        fun swapPayloadWords(frame: ByteArray) {
            var i = 0
            val n = frame.size - 1
            while (i < n) {
                val t = frame[i]
                frame[i] = frame[i + 1]
                frame[i + 1] = t
                i += 2
            }
        }

        /**
         * Pure function: overwrite the first 8 bytes of a MAT frame with the IEC61937
         * preamble, little-endian. MatPacker leaves those bytes zeroed for this purpose.
         */
        fun writeIecPreamble(frame: ByteArray) {
            putLe16(frame, 0, PA)
            putLe16(frame, 2, PB)
            putLe16(frame, 4, PC_TRUEHD)
            putLe16(frame, 6, PD_TRUEHD)
        }

        private fun putLe16(b: ByteArray, off: Int, word: Int) {
            b[off] = (word and 0xFF).toByte()
            b[off + 1] = ((word ushr 8) and 0xFF).toByte()
        }
    }
}

private fun movieAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
    .build()
