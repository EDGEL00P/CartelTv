package com.carteltv.tv.engine.playback

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Least-recently-used map that keeps at most [max] entries. */
class BucketCache<V : Any>(private val max: Int) {
    private val map = object : LinkedHashMap<Long, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, V>?) = size > max
    }

    @Synchronized fun get(key: Long): V? = map[key]
    @Synchronized fun put(key: Long, value: V) { map[key] = value }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size() = map.size
}

/** Stops asking a source that keeps failing, so a slow or unsupported server is not hammered. */
class FailureBreaker(private val limit: Int = 3) {
    private var streak = 0
    val tripped: Boolean @Synchronized get() = streak >= limit
    @Synchronized fun success() { streak = 0 }
    @Synchronized fun failure() { streak++ }
}

/**
 * Preview frames for the seek bar, cut from the stream itself with the platform's frame extractor. Frames are
 * cached per ten-second bucket and fetched one at a time; after repeated failures the source is left alone.
 * Not for streams where an extra request would use a connection slot.
 */
class SeekThumbnails(
    private val url: String,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val cache = BucketCache<Bitmap>(CACHE_FRAMES)
    private val breaker = FailureBreaker()
    private var retriever: MediaMetadataRetriever? = null

    val available: Boolean get() = !breaker.tripped

    /** The frame nearest [positionMs], or null when none could be read. Safe to cancel between buckets. */
    suspend fun frameAt(positionMs: Long): Bitmap? {
        if (breaker.tripped) return null
        val key = bucket(positionMs)
        cache.get(key)?.let { return it }
        return withContext(io) {
            cache.get(key)?.let { return@withContext it }
            val frame = withTimeoutOrNull(FETCH_TIMEOUT_MS) { extract(key) }
            if (frame != null) { breaker.success(); cache.put(key, frame) } else breaker.failure()
            frame
        }
    }

    private fun extract(positionMs: Long): Bitmap? = runCatching {
        val r = retriever ?: MediaMetadataRetriever().also {
            it.setDataSource(url, HashMap())
            retriever = it
        }
        r.getScaledFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
    }.getOrNull()

    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        cache.clear()
    }

    companion object {
        const val BUCKET_MS = 10_000L
        const val WIDTH = 320
        const val HEIGHT = 180
        private const val CACHE_FRAMES = 24
        private const val FETCH_TIMEOUT_MS = 6_000L

        fun bucket(positionMs: Long): Long = positionMs.coerceAtLeast(0L) / BUCKET_MS * BUCKET_MS
    }
}
