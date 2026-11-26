package com.carteltv.tv.engine.stability

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureTest {
    @Test
    fun only_connection_level_errors_are_transient() {
        assertTrue(PlaybackFailure.isTransientNetwork(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(PlaybackFailure.isTransientNetwork(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertFalse(PlaybackFailure.isTransientNetwork(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(PlaybackFailure.isTransientNetwork(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    @Test
    fun session_limit_codes() {
        assertEquals(PlaybackFailure.SessionLimit, PlaybackFailure.fromHttpCode(458))
        assertEquals(PlaybackFailure.SessionLimit, PlaybackFailure.fromHttpCode(429))
    }

    @Test
    fun other_codes_and_null_are_not_session_limit() {
        for (c in listOf(null, 200, 403, 404, 500, 503)) assertEquals(PlaybackFailure.Other, PlaybackFailure.fromHttpCode(c))
    }
}
