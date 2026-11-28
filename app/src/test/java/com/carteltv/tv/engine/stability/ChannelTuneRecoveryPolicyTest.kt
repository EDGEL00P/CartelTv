package com.carteltv.tv.engine.stability

import androidx.media3.common.PlaybackException
import com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy.Failure
import com.carteltv.tv.engine.stability.ChannelTuneRecoveryPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneRecoveryPolicyTest {

    private fun ladder(attempts: Int = 3) = ChannelTuneRecoveryPolicy(StreamReconnectPolicy(maxAttempts = attempts))
        .also { it.attemptStarted(1_000, newTune = true) }

    @Test fun permanentFailureSkipsStraightToTheNextCopy() {
        assertEquals(Step.NextCopy, ladder().onFailure(Failure.PERMANENT, 2_000))
    }

    @Test fun transientFailuresReloadWithBackoffThenMoveOn() {
        val l = ladder(attempts = 2)
        assertEquals(Step.Reload(1_000), l.onFailure(Failure.TRANSIENT, 2_000))
        assertEquals(Step.Reload(2_000), l.onFailure(Failure.TRANSIENT, 3_000))
        assertEquals(Step.NextCopy, l.onFailure(Failure.TRANSIENT, 4_000))
    }

    @Test fun theBudgetEndsReloadingOnAStreamThatNeverPlays() {
        val l = ladder(attempts = 6)
        assertTrue(l.onFailure(Failure.TRANSIENT, 5_000) is Step.Reload)
        assertEquals(Step.NextCopy, l.onFailure(Failure.TRANSIENT, 1_000 + ChannelTuneRecoveryPolicy.BUDGET_MS))
    }

    @Test fun aSustainedPlayEarnsAFreshBudgetAndRetries() {
        val policy = StreamReconnectPolicy(maxAttempts = 1)
        val l = ChannelTuneRecoveryPolicy(policy).also { it.attemptStarted(0, newTune = true) }
        l.onReady(1_000)
        assertTrue(l.onFailure(Failure.TRANSIENT, 1_000 + ChannelTuneRecoveryPolicy.SUSTAINED_MS) is Step.Reload)
        // The budget restarted at that failure, so the next reload is well inside it.
        l.attemptStarted(1_000 + ChannelTuneRecoveryPolicy.SUSTAINED_MS)
        l.onReady(1_000 + ChannelTuneRecoveryPolicy.SUSTAINED_MS + 500)
        assertTrue(l.onFailure(Failure.TRANSIENT, 2 * ChannelTuneRecoveryPolicy.SUSTAINED_MS + 1_500) is Step.Reload)
    }

    @Test fun aBriefPlayDoesNotRestoreTheBudget() {
        val l = ChannelTuneRecoveryPolicy(StreamReconnectPolicy(maxAttempts = 1)).also { it.attemptStarted(0, newTune = true) }
        assertTrue(l.onFailure(Failure.TRANSIENT, 1_000) is Step.Reload)
        l.attemptStarted(2_000)
        l.onReady(2_500)
        assertEquals(Step.NextCopy, l.onFailure(Failure.TRANSIENT, 3_000))
    }

    @Test fun startupTimesOutOnlyBeforeReady() {
        val l = ladder()
        assertFalse(l.startupTimedOut(1_000 + ChannelTuneRecoveryPolicy.STARTUP_MS - 1))
        assertTrue(l.startupTimedOut(1_000 + ChannelTuneRecoveryPolicy.STARTUP_MS))
        l.onReady(5_000)
        assertFalse(l.startupTimedOut(100_000))
    }

    @Test fun aFutureAttemptStartHoldsTheStartupTimerOff() {
        val l = ladder()
        l.attemptStarted(50_000)
        assertFalse(l.startupTimedOut(55_000))
        assertFalse(l.startupTimedOut(59_999))
        assertTrue(l.startupTimedOut(60_000))
    }

    @Test fun deadLinksAndUnreadableStreamsArePermanent() {
        assertEquals(Failure.PERMANENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(Failure.PERMANENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 410))
        assertEquals(Failure.PERMANENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null))
        assertEquals(Failure.PERMANENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null))
    }

    @Test fun panelRefusalsAndNetworkDropsAreTransient() {
        assertEquals(Failure.TRANSIENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 401))
        assertEquals(Failure.TRANSIENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertEquals(Failure.TRANSIENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
        assertEquals(Failure.TRANSIENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertEquals(Failure.TRANSIENT, ChannelTuneRecoveryPolicy.classify(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
    }
}
