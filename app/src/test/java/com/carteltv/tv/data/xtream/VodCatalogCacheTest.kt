package com.carteltv.tv.data.xtream

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VodCatalogCacheTest {
    @Test fun concurrentMissesShareOneFetch() = runBlocking {
        val requestCount = AtomicInteger()
        val client = object : XtreamClient("http://vod-cache-test.local", "user", "pass") {
            override suspend fun vodStreams(categoryId: String?): List<XtreamClient.VodStream> {
                requestCount.incrementAndGet()
                delay(25)
                return listOf(XtreamClient.VodStream(streamId = 1))
            }
        }
        ProviderSession.current = client

        try {
            val results = coroutineScope {
                List(3) { async { VodCatalogCache.all(client) } }.awaitAll()
            }

            assertEquals(1, requestCount.get())
            assertEquals(List(3) { listOf(XtreamClient.VodStream(streamId = 1)) }, results)
        } finally {
            ProviderSession.clear()
        }
    }
}