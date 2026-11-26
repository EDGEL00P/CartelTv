package com.carteltv.tv.engine.playback

import kotlin.math.abs

/**
 * Works out a video's frame rate from the presentation timestamps of frames as they render, for streams whose
 * container reports none. Frame-duration jitter, B-frame ordering and seek jumps are absorbed by taking the
 * median interval of the sorted timestamps.
 */
class FrameRateProbe(private val wanted: Int = WANTED_SAMPLES) {

    private val stamps = LongArray(wanted)
    private var count = 0

    /** Records one rendered frame. Returns true once enough frames are in for [estimate]. */
    @Synchronized
    fun add(presentationTimeUs: Long): Boolean {
        if (count < wanted) stamps[count++] = presentationTimeUs
        return count >= wanted
    }

    @Synchronized
    fun reset() {
        count = 0
    }

    /**
     * Fast estimate from the first [EARLY_SAMPLES] frames, for switching the display mode before a
     * full window arrives (~0.3 s instead of ~1.2 s at 50 fps). Gaps within 0.6x-1.6x of the median
     * are averaged; a seek jump or a repeated frame is discarded by that filter. Returns null until
     * [EARLY_SAMPLES] frames are in or when the timing is unusable.
     */
    @Synchronized
    fun earlyEstimate(): Float? {
        if (count < EARLY_SAMPLES) return null
        val gaps = LongArray(EARLY_SAMPLES - 1) { stamps[it + 1] - stamps[it] }
            .filter { it > 0L }.sorted()
        if (gaps.size < EARLY_SAMPLES / 2) return null
        val median = gaps[gaps.size / 2]
        val clean = gaps.filter { it >= median * 0.6 && it <= median * 1.6 }
        if (clean.isEmpty()) return null
        val fps = 1_000_000f / (clean.sum().toFloat() / clean.size)
        return if (fps in MIN_FPS..MAX_FPS) snapToStandardRate(fps) else null
    }

    /** Snapped frames per second, or null while there are too few frames or the timing is unusable. */
    @Synchronized
    fun estimate(): Float? {
        if (count < wanted) return null
        val sorted = stamps.copyOf(count).also { it.sort() }
        val deltas = LongArray(count - 1) { sorted[it + 1] - sorted[it] }.filter { it > 0 }.sorted()
        if (deltas.size < wanted / 2) return null
        val median = deltas[deltas.size / 2]
        val fps = 1_000_000f / median
        return if (fps in MIN_FPS..MAX_FPS) snapToStandardRate(fps) else null
    }

    companion object {
        const val WANTED_SAMPLES = 60
        private const val EARLY_SAMPLES = 16
        private const val MIN_FPS = 10f
        private const val MAX_FPS = 125f
        private const val NTSC_FILM = 24000f / 1001f
        private val STANDARD = floatArrayOf(
            NTSC_FILM, 24f, 25f, 30000f / 1001f, 30f, 48f, 50f, 60000f / 1001f, 60f,
        )

        /**
         * Snaps a measured rate to the broadcast/film rate it was meant to be. Containers that round frame
         * duration to whole milliseconds yield 23.81, 30.30 and 58.82, which are the 1000/1001 rates, not 24/30/60.
         * Anything not within half a percent of a standard rate is returned unchanged.
         */
        fun snapToStandardRate(fps: Float): Float {
            if (fps <= 0f) return fps
            return when (fps) {
                in 23.80f..23.82f -> NTSC_FILM
                in 30.29f..30.32f -> 30000f / 1001f
                in 58.81f..58.84f -> 60000f / 1001f
                else -> STANDARD.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) <= it * 0.005f } ?: fps
            }
        }
    }
}
