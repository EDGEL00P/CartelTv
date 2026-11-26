@file:androidx.media3.common.util.UnstableApi

// Dialogue Boost — adjustable dialogue enhancement.
// (Speech lift + volume-spike evening). Own implementation for CartelTV's media3
// 1.8 AAR fleet: a BaseAudioProcessor that lifts the center channel on multichannel
// PCM (where film dialogue lives) and applies a speech-band presence lift on stereo.
//
// Applies to DECODED audio (the FFmpeg renderer path). Passthrough bitstreams skip
// all AudioProcessors by nature, so boosting a track prefers decode over passthrough.

package com.carteltv.tv.ui.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

object DialogueBoost {

    /** 0 = OFF, 1 = SUBTLE, 2 = MEDIUM, 3 = STRONG. */
    const val LEVELS = 4

    private val CENTER_GAIN_DB = floatArrayOf(0f, 2f, 4f, 6f)
    private val DUCK = floatArrayOf(1f, 0.92f, 0.8f, 0.68f)
    private val STEREO_BAND_DB = floatArrayOf(0f, 1.5f, 3f, 4.5f)
    private val STEREO_WET = floatArrayOf(0f, 0.5f, 0.6f, 0.7f)

    fun levelName(level: Int): String = when (level) {
        0 -> "Off"; 1 -> "Subtle"; 2 -> "Medium"; 3 -> "Strong"; else -> "Off"
    }

    fun centerGain(level: Int): Float =
        10f.pow(CENTER_GAIN_DB.getOrElse(level) { 0f } / 20f)

    fun duckGain(level: Int): Float = DUCK.getOrElse(level) { 1f }

    fun stereoBandDb(level: Int): Float = STEREO_BAND_DB.getOrElse(level) { 0f }

    fun stereoWet(level: Int): Float = STEREO_WET.getOrElse(level) { 0f }
}

class DialogueBoostProcessor(private val level: Int) : BaseAudioProcessor() {

    // Speech-band peaking biquad (RBJ), one state per channel for stereo path.
    private var bqB0 = 1f; private var bqB1 = 0f; private var bqB2 = 0f
    private var bqA1 = 0f; private var bqA2 = 0f
    private val bqX1 = FloatArray(2); private val bqX2 = FloatArray(2)
    private val bqY1 = FloatArray(2); private val bqY2 = FloatArray(2)

    private var channels = 0
    private var isFloat = false
    private var gainDirty = true
    private var centerGain = 1f
    private var duckGain = 1f
    private var wet = 0f

    override fun isActive(): Boolean = super.isActive() && channels > 0

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        isFloat = enc == C.ENCODING_PCM_FLOAT
        gainDirty = true
        bqX1.fill(0f); bqX2.fill(0f); bqY1.fill(0f); bqY2.fill(0f)
        return inputAudioFormat
    }

    private fun updateGains(sampleRate: Int) {
        centerGain = DialogueBoost.centerGain(level)
        duckGain = DialogueBoost.duckGain(level)
        wet = DialogueBoost.stereoWet(level)
        // Peaking EQ at 2.4 kHz, Q ≈ 1.0 — where consonants live, below the sibilance wash.
        val db = DialogueBoost.stereoBandDb(level)
        val a = 10f.pow(db / 40f)
        val w0 = 2f * PI.toFloat() * 2400f / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / 2f   // Q = 1
        val a0 = 1f + alpha / a
        bqB0 = (1f + a * alpha) / a0
        bqB1 = (-2f * cw) / a0
        bqB2 = (1f - a * alpha) / a0
        bqA1 = (-2f * cw) / a0
        bqA2 = (1f - alpha / a) / a0
        gainDirty = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (gainDirty) updateGains(inputAudioFormat.sampleRate)
        val order = inputBuffer.order()
        val base = inputBuffer.position()
        val frameBytes = channels * (if (isFloat) 4 else 2)
        // Whole frames only: a partial frame left at the end waits for the rest of it.
        val frames = if (frameBytes <= 0) 0 else inputBuffer.remaining() / frameBytes
        val outBytes = frames * frameBytes
        val out = if (isFloat) {
            val f = inputBuffer.asFloatBuffer()
            val ob = replaceOutputBuffer(outBytes)
            ob.order(order)
            val of = ob.asFloatBuffer()
            var i = 0
            while (i < frames) {
                when {
                    channels >= 5 -> {          // L R C LFE ... — dialogue sits in ch 2
                        val l = f.get(i * channels) * duckGain
                        val r = f.get(i * channels + 1) * duckGain
                        val c = f.get(i * channels + 2) * centerGain
                        of.put(l); of.put(r); of.put(clampF(c))
                        for (ch in 3 until channels) of.put(f.get(i * channels + ch) * duckGain)
                    }
                    channels == 2 -> {
                        for (ch in 0..1) {
                            val x = f.get(i * channels + ch)
                            val y = bq(ch, x)
                            of.put(clampF(x * (1f - wet) + y * wet))
                        }
                    }
                    else -> {                    // mono / quad: leave untouched
                        for (ch in 0 until channels) of.put(f.get(i * channels + ch))
                    }
                }
                i++
            }
            ob
        } else {
            val ob = replaceOutputBuffer(outBytes)
            ob.order(order)
            var i = 0
            while (i < frames) {
                when {
                    channels >= 5 -> {
                        val l = inputBuffer.getShort(base + i * channels * 2) * duckGain
                        val r = inputBuffer.getShort(base + (i * channels + 1) * 2) * duckGain
                        val c = inputBuffer.getShort(base + (i * channels + 2) * 2) * centerGain
                        ob.putShort(toShort(l)); ob.putShort(toShort(r)); ob.putShort(toShort(c))
                        for (ch in 3 until channels) {
                            ob.putShort(toShort(inputBuffer.getShort(base + (i * channels + ch) * 2) * duckGain))
                        }
                    }
                    channels == 2 -> {
                        for (ch in 0..1) {
                            val x = inputBuffer.getShort(base + (i * channels + ch) * 2).toFloat()
                            val y = bq(ch, x)
                            ob.putShort(toShort(x * (1f - wet) + y * wet))
                        }
                    }
                    else -> {
                        for (ch in 0 until channels) {
                            ob.putShort(inputBuffer.getShort(base + (i * channels + ch) * 2))
                        }
                    }
                }
                i++
            }
            ob
        }
        // The views read from the input's position and wrote straight into the output, whose own position they never
        // moved. Advance the input by the whole frames read and cut the output down to what was written: the output
        // buffer is reused, so it can be larger than this chunk.
        inputBuffer.position(base + outBytes)
        out.limit(outBytes)
        out.position(0)
    }

    private fun bq(ch: Int, x: Float): Float {
        val idx = if (ch < 2) ch else 0
        val y = bqB0 * x + bqB1 * bqX1[idx] + bqB2 * bqX2[idx] - bqA1 * bqY1[idx] - bqA2 * bqY2[idx]
        bqX2[idx] = bqX1[idx]; bqX1[idx] = x
        bqY2[idx] = bqY1[idx]; bqY1[idx] = y
        return y
    }

    private fun clampF(v: Float): Float = when {
        v > 1f -> 1f; v < -1f -> -1f; else -> v
    }

    private fun toShort(v: Float): Short {
        val i = v.toInt()
        return when {
            i > Short.MAX_VALUE -> Short.MAX_VALUE
            i < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> i.toShort()
        }
    }

    override fun onQueueEndOfStream() { /* nothing buffered beyond input frames */ }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onFlush() { bqX1.fill(0f); bqX2.fill(0f); bqY1.fill(0f); bqY2.fill(0f) }
    override fun onReset() { channels = 0; onFlush() }
}
