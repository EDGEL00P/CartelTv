package com.carteltv.tv.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class HttpRetryPolicyTest {
    private val policy = HttpRetryPolicy(jitter = 0.0)

    @Test fun connectionFailuresRetryWithBackoffThenStop() {
        val net = HttpFailure(HttpFailure.NETWORK_FAILURE_CODE, "HTTP -1")
        assertEquals(HttpRetryPolicy.DEFAULT_BASE_MS, policy.nextDelayMs(net))
        assertEquals(HttpRetryPolicy.DEFAULT_BASE_MS * 2, policy.nextDelayMs(net))
        assertNull("the retry budget is spent", policy.nextDelayMs(net))
    }

    @Test fun serverErrorsRetry() {
        for (code in intArrayOf(500, 502, 503)) {
            assertNotNull("HTTP $code should retry", HttpRetryPolicy(jitter = 0.0).nextDelayMs(HttpFailure(code, "HTTP $code")))
        }
    }

    @Test fun clientAnswersDoNotRetry() {
        for (code in intArrayOf(400, 401, 403, 404, 410, 429)) {
            assertNull("HTTP $code is an answer, not a hiccup", policy.nextDelayMs(HttpFailure(code, "HTTP $code")))
        }
    }

    @Test fun plainIOExceptionsRetryLikeConnectionFailures() {
        assertNotNull(policy.nextDelayMs(IOException("truncated body")))
    }
}
