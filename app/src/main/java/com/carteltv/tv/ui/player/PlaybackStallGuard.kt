package com.carteltv.tv.ui.player

import androidx.media3.common.Player
import com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy

/**
 * Two timers for a player that has no recovery ladder of its own (YouTube, a multi-view tile), pure
 * so that they can be tested: a stream that never reaches READY within [startupMs] of an attempt,
 * and one that sits in BUFFERING for [stallMs] while it should be playing. Each costs a bounded
 * number of automatic retries; once those are spent the surface is told to give up instead of
 * spinning forever. Time the player spends paused or in the background does not count.
 */
internal class PlaybackStallGuard(
    private val maxRetries: Int = 2,
    private val startupMs: Long = ChannelTuneRecoveryPolicy.STARTUP_MS,
    private val stallMs: Long = STALL_MS,
    private val sustainedMs: Long = ChannelTuneRecoveryPolicy.SUSTAINED_MS,
) {
    enum class Kind { STARTUP, STALL }

    sealed interface Outcome {
        /** Nothing to do. */
        data object None : Outcome

        /** The surface should reload the stream; the startup clock restarts by itself. */
        data object Retry : Outcome

        /** The retries are spent. The surface must say so and stop waiting. */
        data class GiveUp(val kind: Kind) : Outcome
    }

    private var retries = 0
    private var attemptStartedMs = 0L
    private var readySinceMs: Long? = null
    private var stalledSinceMs: Long? = null
    private var lastCheckMs = 0L
    private var givenUp = false

    /** A (re)prepare begins, so the startup clock restarts. */
    fun attemptStarted(nowMs: Long) {
        attemptStartedMs = nowMs
        readySinceMs = null
        stalledSinceMs = null
        givenUp = false
    }

    /** A fresh start for the same player: the retry budget comes back. */
    fun reset() {
        retries = 0
        lastCheckMs = 0L
        attemptStarted(0L)
    }

    /** Call about twice a second. [playing] is the player's playWhenReady. */
    fun check(nowMs: Long, playbackState: Int, playing: Boolean): Outcome {
        val step = if (lastCheckMs > 0L) nowMs - lastCheckMs else 0L
        lastCheckMs = nowMs
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            readySinceMs = null
            stalledSinceMs = null
            return Outcome.None
        }
        if (!playing) {
            // Stand still while the viewer or the system has the player paused.
            attemptStartedMs += step
            stalledSinceMs = stalledSinceMs?.plus(step)
            return Outcome.None
        }
        if (givenUp) return Outcome.None
        if (playbackState == Player.STATE_READY) {
            stalledSinceMs = null
            val since = readySinceMs ?: nowMs.also { readySinceMs = it }
            // Playing for a while counts as healthy and hands back the retries, as ChannelTuneRecoveryPolicy does.
            if (nowMs - since >= sustainedMs) retries = 0
            return Outcome.None
        }
        // BUFFERING while the player wants to be playing.
        if (readySinceMs == null) {
            return if (nowMs - attemptStartedMs < startupMs) Outcome.None else runOut(Kind.STARTUP, nowMs)
        }
        val stalledAt = stalledSinceMs ?: nowMs.also { stalledSinceMs = it }
        return if (nowMs - stalledAt < stallMs) Outcome.None else runOut(Kind.STALL, nowMs)
    }

    private fun runOut(kind: Kind, nowMs: Long): Outcome {
        if (retries >= maxRetries) {
            givenUp = true
            readySinceMs = null
            stalledSinceMs = null
            return Outcome.GiveUp(kind)
        }
        retries++
        // The reload that follows has to reach READY again, like a first attempt.
        attemptStartedMs = nowMs
        readySinceMs = null
        stalledSinceMs = null
        return Outcome.Retry
    }

    companion object {
        /** How long a playing stream may sit rebuffering before it counts as stalled. */
        const val STALL_MS = 20_000L
    }
}
