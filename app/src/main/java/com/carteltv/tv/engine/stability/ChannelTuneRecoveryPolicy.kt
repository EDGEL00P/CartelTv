package com.carteltv.tv.engine.stability

import androidx.media3.common.PlaybackException

/**
 * The recovery ladder for one live channel, pure: the player feeds it events with the current time and does what
 * it answers. Each failure goes down the rungs and every rung is bounded:
 *
 * 1. A failure the stream cannot survive (a dead link, an unreadable or unsupported stream) goes straight to the
 *    next quality copy. Retrying a 404 six times with backoff only delays the zap.
 * 2. Any other failure reloads the same stream after [StreamReconnectPolicy]'s backoff, until the policy is spent.
 * 3. Then the next copy; with none left the channel is unavailable.
 *
 * A stream that never plays also runs out of time: no READY within [STARTUP_MS] of an attempt is a failure, and
 * [BUDGET_MS] after the tune began the ladder stops reloading. Playing for [SUSTAINED_MS] counts as healthy and
 * hands the stream a fresh budget, so one bad segment after an hour does not cost it its retries.
 */
class ChannelTuneRecoveryPolicy(private val policy: StreamReconnectPolicy) {

    enum class Failure { PERMANENT, TRANSIENT }

    sealed interface Step {
        data class Reload(val delayMs: Long) : Step
        data object NextCopy : Step
    }

    private var budgetStartMs = 0L
    private var attemptStartMs = 0L
    private var readySinceMs: Long? = null

    /** A (re)prepare or a switch of copy begins. [newTune] also restarts the overall budget. */
    fun attemptStarted(nowMs: Long, newTune: Boolean = false) {
        attemptStartMs = nowMs
        readySinceMs = null
        if (newTune) {
            budgetStartMs = nowMs
            policy.reset()
        }
    }

    fun onReady(nowMs: Long) {
        if (readySinceMs == null) readySinceMs = nowMs
    }

    /** No picture yet and the attempt has taken longer than [STARTUP_MS]. */
    fun startupTimedOut(nowMs: Long): Boolean = readySinceMs == null && attemptStartMs > 0 && nowMs - attemptStartMs >= STARTUP_MS

    fun onFailure(failure: Failure, nowMs: Long): Step {
        val since = readySinceMs
        readySinceMs = null
        if (since != null && nowMs - since >= SUSTAINED_MS) {
            budgetStartMs = nowMs
            policy.reset()
        }
        if (failure == Failure.PERMANENT) return Step.NextCopy
        if (nowMs - budgetStartMs >= BUDGET_MS) return Step.NextCopy
        return policy.nextDelayMs()?.let { Step.Reload(it) } ?: Step.NextCopy
    }

    companion object {
        const val STARTUP_MS = 10_000L
        const val BUDGET_MS = 30_000L
        const val SUSTAINED_MS = 30_000L

        private val PARSE_ERRORS = (3001..3004).toSet()
        private val DECODER_SETUP_ERRORS = setOf(4001, 4002, 4004, 4005)

        /**
         * 400, 404, 410 and 451 never heal. 401 and 403 do not count: an Xtream panel answers them for its
         * connection limit and lapsed sessions, which clear on their own.
         */
        fun classify(errorCode: Int, httpCode: Int?): Failure = when {
            httpCode == 400 || httpCode == 404 || httpCode == 410 || httpCode == 451 -> Failure.PERMANENT
            errorCode in PARSE_ERRORS || errorCode in DECODER_SETUP_ERRORS -> Failure.PERMANENT
            else -> Failure.TRANSIENT
        }

        fun classify(error: PlaybackException): Failure {
            var code: Int? = null
            var t: Throwable? = error
            while (t != null && code == null) {
                if (t is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) code = t.responseCode
                t = t.cause
            }
            return classify(error.errorCode, code)
        }
    }
}
