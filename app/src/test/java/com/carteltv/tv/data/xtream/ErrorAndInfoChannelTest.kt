package com.carteltv.tv.data.xtream

import androidx.media3.common.PlaybackException
import com.carteltv.tv.engine.stability.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorAndInfoChannelTest {

    @Test fun missingFileReadsAsUnavailableChannel() {
        val msg = PlaybackFailure.userMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertTrue(msg, msg.contains("not available from your provider"))
        assertFalse(msg.contains("FileNotFoundException"))
    }

    @Test fun httpStatusDecidesTheMessage() {
        assertTrue(PlaybackFailure.userMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404).contains("not available"))
        assertTrue(PlaybackFailure.userMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403).contains("sign-in"))
    }

    @Test fun networkAndFormatAndFallback() {
        assertTrue(PlaybackFailure.userMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).contains("network"))
        assertTrue(PlaybackFailure.userMessage(PlaybackException.ERROR_CODE_DECODING_FAILED).contains("format"))
        assertEquals("The stream could not be played.", PlaybackFailure.userMessage(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test fun noticeEntriesAreInfoChannels() {
        for (n in listOf("Account Information", "ACCOUNT INFO", "  Subscription Status ", "Expiry Details")) {
            assertTrue(n, NoticeChannels.isNotice(n))
        }
    }

    @Test fun realChannelsAreNotInfoChannels() {
        for (n in listOf("FOX 29 (WUTV)", "Account Information Channel HD", "BBC One", "Sky Sports Information", "Accounting Today")) {
            assertFalse(n, NoticeChannels.isNotice(n))
        }
    }
}
