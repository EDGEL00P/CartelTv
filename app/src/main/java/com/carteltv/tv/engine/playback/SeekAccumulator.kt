package com.carteltv.tv.engine.playback

/**
 * Left/right presses that add up instead of each seeking on its own. Playback goes on while the target moves;
 * the run ends with one seek once the keys have been quiet for [SETTLE_MS]. Steps grow with a held or rapidly
 * repeated key so a two-hour film is crossable without a hundred presses.
 */
class SeekAccumulator {
    private var target: Long? = null
    private var lastPressMs = 0L
    private var streak = 0

    /** The position the pending seek will land on, or null when no run is open. */
    val pendingMs: Long? get() = target

    /**
     * A seek key went down. [direction] is -1 or 1; [playbackMs] is where playback stands. Returns the new target,
     * clamped to the file; [durationMs] <= 0 means the length is unknown and only the lower bound applies.
     */
    fun press(direction: Int, nowMs: Long, playbackMs: Long, durationMs: Long): Long {
        streak = if (target != null && nowMs - lastPressMs <= STREAK_GAP_MS) streak + 1 else 0
        lastPressMs = nowMs
        val base = target ?: playbackMs.coerceAtLeast(0L)
        val moved = (base + direction * stepFor(streak)).coerceAtLeast(0L)
        val next = if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
        target = next
        return next
    }

    /** True once the keys have been quiet long enough for the run to commit. */
    fun due(nowMs: Long): Boolean = target != null && nowMs - lastPressMs >= SETTLE_MS

    /** Ends the run and returns where to seek, or null when none was open. */
    fun commit(): Long? = target.also { cancel() }

    fun cancel() {
        target = null
        streak = 0
    }

    companion object {
        const val SETTLE_MS = 800L
        private const val STREAK_GAP_MS = 400L

        /** One press of a seek button or a single left/right tap, and the first rung of [stepFor]. */
        const val BASE_STEP_MS = 10_000L

        /** 10 s for the first few presses, then 30 s, then a minute. */
        fun stepFor(streak: Int): Long = when {
            streak < 3 -> BASE_STEP_MS
            streak < 7 -> 3 * BASE_STEP_MS
            else -> 6 * BASE_STEP_MS
        }

        /** Where playback restarts when resuming at [rememberedMs]: a little earlier, so the viewer regains context. */
        fun resumeFrom(rememberedMs: Long): Long = if (rememberedMs <= RESUME_BACKOFF_MS * 2) 0L else rememberedMs - RESUME_BACKOFF_MS

        private const val RESUME_BACKOFF_MS = 5_000L
    }
}
