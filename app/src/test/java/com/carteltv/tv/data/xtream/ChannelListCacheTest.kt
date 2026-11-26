package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.xtream.XtreamClient.LiveStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelListCacheTest {

    private val streams = listOf(
        LiveStream(num = 1, name = "BBC One", streamId = 101, icon = "http://x/bbc.png", epgId = "bbc1", categoryId = "7"),
        LiveStream(num = 2, name = "Sky Sports", streamId = 102),
    )
    private val now = 1_000_000_000L

    @Test fun roundTripKeepsEveryField() {
        val text = ChannelListCache.serialize("k", now, streams)
        assertEquals(streams, ChannelListCache.parse(text, "k", now + 60_000L))
    }

    @Test fun aDifferentKeyIsNotServed() {
        val text = ChannelListCache.serialize("provider-a", now, streams)
        assertNull(ChannelListCache.parse(text, "provider-b", now))
    }

    @Test fun anOldSnapshotIsNotServed() {
        val text = ChannelListCache.serialize("k", now, streams)
        assertNotNull(ChannelListCache.parse(text, "k", now + ChannelListCache.MAX_AGE_MS))
        assertNull(ChannelListCache.parse(text, "k", now + ChannelListCache.MAX_AGE_MS + 1))
    }

    @Test fun aSnapshotFromTheFutureIsNotServed() {
        val text = ChannelListCache.serialize("k", now + 5_000L, streams)
        assertNull(ChannelListCache.parse(text, "k", now))
    }

    @Test fun anEmptyListIsNotServed() {
        assertNull(ChannelListCache.parse(ChannelListCache.serialize("k", now, emptyList()), "k", now))
    }

    @Test fun garbageIsNotServed() {
        assertNull(ChannelListCache.parse("", "k", now))
        assertNull(ChannelListCache.parse("{not json", "k", now))
        assertNull(ChannelListCache.parse("[]", "k", now))
    }

    @Test fun writeThenReadThroughAFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "carteltv-cache-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            ChannelListCache.write(dir, "k", now, streams)
            assertEquals(streams, ChannelListCache.read(dir, "k", now))
            assertNull(ChannelListCache.read(dir, "other", now))
            assertNull(ChannelListCache.read(File(dir, "missing"), "k", now))
        } finally {
            dir.deleteRecursively()
        }
    }
}
