package com.carteltv.tv.engine.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkAndThumbsTest {

    @Test fun onlyDefiniteAnswersMeanGone() {
        listOf(400, 401, 403, 404, 410).forEach { assertTrue("$it", StreamLinkCheck.isGone(it)) }
        listOf(200, 206, 301, 405, 429, 500, 503).forEach { assertFalse("$it", StreamLinkCheck.isGone(it)) }
    }

    @Test fun probesOnlyRemoteVodLinksNotOnTheProvider() {
        val base = "http://panel.example:8080"
        assertTrue(StreamLinkCheck.shouldProbe("https://cdn.example/f.mkv", "debrid:1", base))
        assertFalse(StreamLinkCheck.shouldProbe("$base/movie/u/p/1.mkv", "movie:1", base))
        assertFalse(StreamLinkCheck.shouldProbe("https://cdn.example/f.mkv", "live:9", base))
        assertFalse(StreamLinkCheck.shouldProbe("https://cdn.example/f.mkv", "recording:a", base))
        assertFalse(StreamLinkCheck.shouldProbe("content://x/1", "debrid:1", base))
        assertTrue(StreamLinkCheck.shouldProbe("http://a/b", "x", null))
    }

    @Test fun anUnreachableHostCountsAsAlive() {
        assertTrue(StreamLinkCheck.isAlive("http://127.0.0.1:1/never", timeoutMs = 300))
    }

    @Test fun bucketsRoundDownToTenSeconds() {
        assertEquals(0L, SeekThumbnails.bucket(9_999))
        assertEquals(10_000L, SeekThumbnails.bucket(10_000))
        assertEquals(120_000L, SeekThumbnails.bucket(129_500))
        assertEquals(0L, SeekThumbnails.bucket(-5))
    }

    @Test fun cacheEvictsTheLeastRecentlyUsed() {
        val c = BucketCache<String>(2)
        c.put(1, "a"); c.put(2, "b")
        c.get(1)
        c.put(3, "c")
        assertEquals("a", c.get(1))
        assertNull(c.get(2))
        assertEquals(2, c.size())
    }

    @Test fun breakerTripsAfterRepeatedFailuresAndResetsOnSuccess() {
        val b = FailureBreaker(3)
        b.failure(); b.failure()
        assertFalse(b.tripped)
        b.success()
        b.failure(); b.failure()
        assertFalse(b.tripped)
        b.failure()
        assertTrue(b.tripped)
    }
}
