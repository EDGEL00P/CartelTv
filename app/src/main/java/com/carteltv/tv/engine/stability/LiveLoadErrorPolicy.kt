@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.engine.stability

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Load errors on a live channel, tuned to sit under [ChannelTuneRecoveryPolicy] instead of above it.
 *
 * Media3's stock policy retries a failing load up to three times with a linear backoff inside the
 * same URL, and a progressive live stream up to six times; a flaky IPTV server spends that whole
 * budget on one dead segment while the app's ladder — jittered backoff, then the next quality
 * copy — could have taken over sooner. So:
 *
 * 1. Media and media-initialization segments fail fast: [SEGMENT_RETRY_COUNT] quick tries, then
 *    the error surfaces to the player and the ladder reloads or switches copies. The retries stay
 *    quick and bounded ([SEGMENT_RETRY_DELAY_MS] each) because the stock first retry is immediate,
 *    which only hammers a provider that is already struggling.
 * 2. A progressive live stream gets [PROGRESSIVE_LIVE_RETRY_COUNT] tries — more than a segment,
 *    because a plain TS reload is cheap and often just a dropped connection — but not the stock six.
 * 3. Everything else (manifests, DRM, ads) keeps the stock behaviour.
 *
 * Non-retriable errors (ParserException and friends) are still refused outright by the stock policy
 * this extends, and VOD keeps the stock policy entirely: its failures have no ladder to catch them.
 */
class LiveLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = when (dataType) {
        C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION -> SEGMENT_RETRY_COUNT
        C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> PROGRESSIVE_LIVE_RETRY_COUNT
        else -> super.getMinimumLoadableRetryCount(dataType)
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        // The stock answer already refuses non-retriable errors with TIME_UNSET; keep that.
        val stockDelayMs = super.getRetryDelayMsFor(loadErrorInfo)
        if (stockDelayMs == C.TIME_UNSET) return stockDelayMs
        return when (loadErrorInfo.mediaLoadData.dataType) {
            C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION ->
                SEGMENT_RETRY_DELAY_MS * loadErrorInfo.errorCount
            else -> stockDelayMs
        }
    }

    companion object {
        /** Segment tries before the error is handed to the recovery ladder. */
        const val SEGMENT_RETRY_COUNT = 2

        /** Progressive-TS tries: cheap reloads deserve one more chance than a single segment. */
        const val PROGRESSIVE_LIVE_RETRY_COUNT = 3

        /** Wait before each segment try, in milliseconds. */
        const val SEGMENT_RETRY_DELAY_MS = 500L
    }
}
