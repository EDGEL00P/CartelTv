@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.engine.stability

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LiveLoadErrorPolicyTest {
    private val policy = LiveLoadErrorPolicy()

    private fun info(dataType: Int, errorCount: Int, cause: IOException = DataSourceException(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)) =
        LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo.Builder(0L, DataSpec(Uri.parse("https://provider.example/seg.ts")), 0L).build(),
            MediaLoadData(dataType),
            cause,
            errorCount,
        )

    @Test fun mediaSegmentsFailFastToTheLadder() {
        assertEquals(LiveLoadErrorPolicy.SEGMENT_RETRY_COUNT, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(LiveLoadErrorPolicy.SEGMENT_RETRY_COUNT, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_INITIALIZATION))
        assertEquals(LiveLoadErrorPolicy.SEGMENT_RETRY_COUNT, 2)
    }

    @Test fun progressiveLiveGetsOneMoreTryButNotTheStockSix() {
        assertEquals(LiveLoadErrorPolicy.PROGRESSIVE_LIVE_RETRY_COUNT, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE))
        assertEquals(LiveLoadErrorPolicy.PROGRESSIVE_LIVE_RETRY_COUNT, 3)
    }

    @Test fun manifestsKeepTheStockRetryCount() {
        assertEquals(
            DefaultLoadErrorHandlingPolicy().getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST),
            policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MANIFEST),
        )
    }

    @Test fun segmentRetriesAreQuickAndBounded() {
        assertEquals(LiveLoadErrorPolicy.SEGMENT_RETRY_DELAY_MS * 1, policy.getRetryDelayMsFor(info(C.DATA_TYPE_MEDIA, 1)))
        assertEquals(LiveLoadErrorPolicy.SEGMENT_RETRY_DELAY_MS * 2, policy.getRetryDelayMsFor(info(C.DATA_TYPE_MEDIA, 2)))
    }

    @Test fun otherLoadsKeepTheStockBackoff() {
        val stock = DefaultLoadErrorHandlingPolicy()
        assertEquals(stock.getRetryDelayMsFor(info(C.DATA_TYPE_MANIFEST, 1)), policy.getRetryDelayMsFor(info(C.DATA_TYPE_MANIFEST, 1)))
        assertEquals(stock.getRetryDelayMsFor(info(C.DATA_TYPE_MANIFEST, 3)), policy.getRetryDelayMsFor(info(C.DATA_TYPE_MANIFEST, 3)))
    }

    @Test fun nonRetriableErrorsAreStillRefused() {
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(info(C.DATA_TYPE_MEDIA, 1, ParserException.createForMalformedContainer("bad segment", null))),
        )
    }
}
