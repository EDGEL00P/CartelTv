package com.carteltv.tv.data.weather
import com.carteltv.tv.core.suspendRunCatching

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import android.graphics.Bitmap
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RadarFeed over OpenStreetMap tiles (OpenStreetMap data), all keyless. LibreWXR's public server (api.librewxr.net, CC-BY-4.0) is
 * the primary source: a RainViewer-compatible API fusing NOAA MRMS/RRQPE, EUMETNET OPERA, ECCC, JMA, CWA, MET Malaysia,
 * PAGASA and other national radars, with a 60-minute nowcast and zoom to 12. RainViewer is the fallback: since 1 Jan 2026
 * its free tier is past frames only, capped at zoom 7 and 100 requests/IP/minute (rainviewer.com/api/transition-faq.html).
 */
object RadarFeed {
    enum class Provider(val maxZoom: Int, val manifestUrl: String, val attribution: String) {
        LIBREWXR(
            12, "https://api.librewxr.net/public/weather-maps.json",
            "Radar © LibreWXR (CC-BY-4.0): NOAA MRMS/RRQPE, EUMETNET OPERA, ECCC, JMA, CWA, MET Malaysia, PAGASA, DPC Italy (CC-BY-SA-4.0) · Map © OpenStreetMap contributors",
        ),
        RAINVIEWER(7, "https://api.rainviewer.com/public/weather-maps.json", "Radar © RainViewer · Map © OpenStreetMap contributors"),
    }

    data class Frame(val timeSec: Long, val path: String)
    data class Manifest(
        val provider: Provider,
        val host: String,
        val past: List<Frame>,
        val nowcast: List<Frame>,
        val fetchedAtMs: Long,
    ) {
        /** Observed frames first, then LibreWXR's forward extrapolation: one index space for playback and the timeline. */
        val frames: List<Frame> = past + nowcast
        /** Index of the newest observation — what the page opens on and what Refresh jumps back to. */
        val latestIndex: Int get() = past.lastIndex
        val hasNowcast get() = nowcast.isNotEmpty()
    }

    const val MIN_ZOOM = 3
    const val TILT_DEG = 56f
    const val MAX_FRAMES = 12
    const val MAX_NOWCAST_FRAMES = 4
    /** NEXRAD Level III; the legend in RadarView and level() below are calibrated to this palette. */
    private const val COLOR_SCHEME = 6

    /** NEXRAD Level III style, dBZ 10…70: cyan, blue, greens, yellow, orange, reds, magenta. ARGB. */
    val legend: List<Long> = listOf(
        0xFF04E9E7, 0xFF019FF4, 0xFF02FD02, 0xFF01C501, 0xFF008E00, 0xFFFDF802, 0xFFFD9500, 0xFFFD0000, 0xFFBC0000, 0xFFF800FD,
    )

    /** Representative dBZ of each NEXRAD Level III band in [legend], read from LibreWXR's color_table.csv. */
    val dbzByLevel: List<Int> = listOf(2, 12, 22, 27, 32, 37, 47, 52, 60, 67)

    fun dbz(level: Int): Int = dbzByLevel[(level - 1).coerceIn(0, dbzByLevel.lastIndex)]

    /** Next frame in a single pass through the timeline; null at the end so playback stops instead of looping. */
    fun nextFrame(index: Int, count: Int): Int? = (index + 1).takeIf { it in 0 until count }

    /** 1..[legend].size for the echo colour at [argb] (nearest legend entry), 0 when the pixel carries no echo. */
    fun level(argb: Int): Int {
        if ((argb ushr 24) < 40) return 0
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        var best = 0
        var bestD = Int.MAX_VALUE
        legend.forEachIndexed { i, c ->
            val dr = r - (c.toInt() shr 16 and 0xFF)
            val dg = g - (c.toInt() shr 8 and 0xFF)
            val db = b - (c.toInt() and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best + 1
    }

    fun intensityWord(level: Int): String = when {
        level <= 0 -> "Dry"
        level <= 2 -> "Light rain"
        level <= 5 -> "Moderate rain"
        level <= 7 -> "Heavy rain"
        else -> "Intense"
    }

    private const val MANIFEST_TTL_MS = 4 * 60_000L
    @Volatile private var manifest: Manifest? = null

    /**
     * Pure parser so a partial or malformed feed degrades to fewer frames instead of taking the radar down. LibreWXR
     * advertises `nowcast`; RainViewer's restricted tier does not, so an absent array simply means no forecast frames.
     */
    internal fun parseManifest(raw: String, p: Provider, now: Long): Manifest? = runCatching {
        val root = HttpSupport.json.parseToJsonElement(raw).jsonObject
        val host = root["host"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val radar = root["radar"]?.jsonObject ?: return@runCatching null
        fun frames(key: String): List<Frame> = radar[key]?.jsonArray?.mapNotNull { e ->
            val o = e.jsonObject
            val t = o["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val path = o["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Frame(t, path)
        }?.sortedBy { it.timeSec }.orEmpty()
        val past = frames("past").takeLast(MAX_FRAMES)
        if (past.isEmpty()) return@runCatching null
        Manifest(p, host, past, frames("nowcast").take(MAX_NOWCAST_FRAMES), now)
    }.getOrNull()

    private suspend fun fetch(p: Provider, now: Long): Manifest? = withContext(Dispatchers.IO) {
        val raw = suspendRunCatching { HttpSupport.get(p.manifestUrl) }.getOrNull() ?: return@withContext null
        parseManifest(raw, p, now)
    }

    suspend fun manifest(force: Boolean = false): Manifest? {
        val now = System.currentTimeMillis()
        manifest?.takeIf { !force && now - it.fetchedAtMs < MANIFEST_TTL_MS }?.let { return it }
        val fresh = Provider.entries.firstNotNullOfOrNull { fetch(it, now) }
        if (fresh != null) manifest = fresh
        return fresh ?: manifest
    }

    /** LibreWXR can draw motion arrows server-side; RainViewer's free tier ignores query parameters. */
    fun radarUrl(m: Manifest, f: Frame, z: Int, x: Int, y: Int, arrows: Boolean = false): String {
        val tile = "${m.host}${f.path}/256/$z/$x/$y/$COLOR_SCHEME/1_1.png"
        return if (arrows && m.provider == Provider.LIBREWXR) "$tile?arrows=light" else tile
    }

    fun terrainUrl(z: Int, x: Int, y: Int) = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png"

    fun baseUrl(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"
}

/**
 * Shared bitmap tile cache with a byte budget, in-flight de-duplication, bounded concurrency and a short
 * negative cache. [version] ticks whenever a tile lands so canvases repaint without owning any state.
 */
object TileCache {
    // A quarter of the heap, 96 MB at most: twelve radar frames over a tilted viewport need ~35 MB, and a smaller
    // budget evicts frames faster than the loop can show them, which freezes playback.
    private val BUDGET_KB = (Runtime.getRuntime().maxMemory() / 1024 / 4).coerceIn(16L * 1024, 96L * 1024).toInt()
    private const val FAIL_BACKOFF_MS = 20_000L
    private val ua = mapOf("User-Agent" to com.carteltv.tv.data.network.OkHttpTransport.USER_AGENT)

    private val lru = object : LruCache<String, ImageBitmap>(BUDGET_KB) {
        override fun sizeOf(key: String, value: ImageBitmap) = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }
    private val inflight = HashMap<String, CompletableDeferred<ImageBitmap?>>()
    private val failedAt = HashMap<String, Long>()
    private val lock = Mutex()
    private val permits = Semaphore(6)

    var version by mutableIntStateOf(0)
        private set

    fun peek(url: String): ImageBitmap? = synchronized(lru) { lru.get(url) }

    /** Drops decoded tiles under memory pressure; they are refetched on demand. */
    fun trim(critical: Boolean) {
        synchronized(lru) { if (critical) lru.evictAll() else lru.trimToSize(BUDGET_KB / 4) }
    }

    /** Decodes a PNG at 1/[sample] size; radar echoes are soft blobs, so a coarser decode costs nothing visible. */
    fun png(sample: Int = 1): (ByteArray) -> ImageBitmap? = { bytes ->
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.asImageBitmap()
    }

    private val fullPng = png()

    /** [key] names the decoded result so one download can be cached per treatment. */
    suspend fun load(url: String, key: String = url, decode: (ByteArray) -> ImageBitmap? = fullPng): ImageBitmap? {
        peek(key)?.let { return it }
        val mine = CompletableDeferred<ImageBitmap?>()
        val existing = lock.withLock {
            if ((failedAt[key] ?: 0L) + FAIL_BACKOFF_MS > System.currentTimeMillis()) return null
            inflight[key] ?: run { inflight[key] = mine; null }
        }
        if (existing != null) return existing.await()

        var bmp: ImageBitmap? = null
        var cancelled = false
        try {
            bmp = permits.withPermit {
                val bytes = HttpSupport.getBytes(url, ua, connectMs = 6_000, readMs = 10_000)
                withContext(Dispatchers.IO) { decode(bytes) }
            }
            if (bmp != null) {
                synchronized(lru) { lru.put(key, bmp) }
                version++
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (_: Exception) {
        } finally {
            // Cleanup must survive cancellation: a skipped remove/complete would strand every waiter on this URL.
            withContext(NonCancellable) {
                lock.withLock {
                    inflight.remove(key)
                    val now = System.currentTimeMillis()
                    if (bmp != null) failedAt.remove(key)
                    else if (!cancelled) {
                        failedAt[key] = now
                        if (failedAt.size > 64) failedAt.values.removeAll { it + FAIL_BACKOFF_MS <= now }
                    }
                }
                mine.complete(bmp)
            }
        }
        return bmp
    }
}


/** Terrarium elevation tile -> translucent shading (shadow black, lit slopes white) laid over the dark basemap. */
object Hillshade {
    private const val SAMPLE = 2

    fun render(z: Int, ty: Int): (ByteArray) -> ImageBitmap? = { bytes ->
        val o = BitmapFactory.Options().apply { inSampleSize = SAMPLE }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.let { dem ->
            val w = dem.width
            val h = dem.height
            val px = IntArray(w * h)
            dem.getPixels(px, 0, w, 0, 0, w, h)
            dem.recycle()
            val elev = FloatArray(w * h) { i ->
                val p = px[i]
                (((p shr 16 and 0xFF) * 256f + (p shr 8 and 0xFF) + (p and 0xFF) / 256f) - 32768f).coerceAtLeast(0f)
            }
            val n = (1 shl z).toDouble()
            val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * (ty + 0.5) / n))))
            val cell = (156543.03392 * kotlin.math.cos(Math.toRadians(lat)) / n * 256.0 / w).toFloat()
            val exaggeration = if (z <= 7) 5f else if (z <= 10) 3f else 2f
            val zenith = Math.toRadians(45.0)
            val azimuth = Math.toRadians(315.0)
            val flat = kotlin.math.cos(zenith).toFloat()
            fun e(x: Int, y: Int) = elev[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
            for (y in 0 until h) for (x in 0 until w) {
                val dx = ((e(x + 1, y - 1) + 2 * e(x + 1, y) + e(x + 1, y + 1)) - (e(x - 1, y - 1) + 2 * e(x - 1, y) + e(x - 1, y + 1))) / (8 * cell)
                val dy = ((e(x - 1, y + 1) + 2 * e(x, y + 1) + e(x + 1, y + 1)) - (e(x - 1, y - 1) + 2 * e(x, y - 1) + e(x + 1, y - 1))) / (8 * cell)
                val slope = kotlin.math.atan(exaggeration * kotlin.math.hypot(dx, dy))
                val aspect = kotlin.math.atan2(dy, -dx)
                val shade = (kotlin.math.cos(zenith) * kotlin.math.cos(slope) +
                    kotlin.math.sin(zenith) * kotlin.math.sin(slope) * kotlin.math.cos(azimuth - aspect)).toFloat()
                val d = shade - flat
                px[y * w + x] = if (d < 0) (minOf(190, (-d * 330).toInt()) shl 24) else (minOf(110, (d * 200).toInt()) shl 24) or 0xFFFFFF
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}
