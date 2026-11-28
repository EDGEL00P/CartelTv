package com.carteltv.tv.data.network

import com.carteltv.tv.engine.stability.StreamReconnectPolicy
import java.io.IOException

/**
 * A failed HTTP call with its status code attached, so retry decisions can be made by code.
 * [NETWORK_FAILURE_CODE] is the transport's own marker for a call that never answered.
 */
class HttpFailure(val code: Int, message: String) : IOException(message) {
    /** Only connection-level failures and 5xx answers deserve another attempt: a working server's 4xx is an answer, not a hiccup. */
    val isRetryable: Boolean get() = code == NETWORK_FAILURE_CODE || code >= 500

    companion object {
        const val NETWORK_FAILURE_CODE = -1
    }
}

/**
 * Whether a failed idempotent GET deserves another attempt, and after how long. Pure so it can
 * be unit-tested without a transport.
 *
 * An Xtream panel answers 429 for its connection limit and 401/403 for lapsed sessions — none
 * of those clear on a retry, so [HttpFailure] gates them out; only connection failures and 5xx
 * pass. The delays reuse [StreamReconnectPolicy]'s backoff and jitter so many clients dropped by
 * one outage do not reconnect on the same tick.
 */
class HttpRetryPolicy(
    maxAttempts: Int = DEFAULT_ATTEMPTS,
    baseMs: Long = DEFAULT_BASE_MS,
    jitter: Double = 0.25,
) {
    private val backoff = StreamReconnectPolicy(maxAttempts = maxAttempts, baseMs = baseMs, capMs = DEFAULT_CAP_MS, jitter = jitter)

    /** Delay before the next retry of a failed GET, or null when the failure should stand. */
    fun nextDelayMs(failure: IOException): Long? {
        if (failure is HttpFailure && !failure.isRetryable) return null
        return backoff.nextDelayMs()
    }

    companion object {
        const val DEFAULT_ATTEMPTS = 2
        const val DEFAULT_BASE_MS = 400L
        const val DEFAULT_CAP_MS = 1_600L
    }
}
