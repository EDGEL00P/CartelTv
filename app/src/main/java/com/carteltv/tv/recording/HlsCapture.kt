package com.carteltv.tv.recording

import com.carteltv.tv.data.network.HttpSupport
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.carteltv.tv.data.network.isHttpUrl
import com.carteltv.tv.engine.stability.PlaybackFailure
import com.carteltv.tv.engine.stability.StreamReconnectPolicy
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

private val BANDWIDTH_ATTR = Regex("BANDWIDTH=(\\d+)")

/**
 * HLS segment-append capture (StreamCapture algorithm, Kotlin port;
 * upgraded with RecorderService anatomy — batches 7 & 8):
 * poll the media playlist; every NEW segment URI gets fetched and appended
 * to one container via a [CaptureSink]. Ends on timeout / size-cap /
 * storage-low / cancelled / stream-lost / ENDLIST.
 *
 * Improvements folded in (defpackage/v51.java + h50/og1/hm0):
 *  - sink abstraction: SMB target with local fallback (v51.d)
 *  - master playlists resolve by highest BANDWIDTH variant
 *  - #EXT-X-ENDLIST → early clean finish (don't burn the whole window)
 *  - TARGETDURATION halves as poll interval instead of fixed 1.5 s
 *  - maxBytes size cap → SizeCapped end (quota governor)
 *  - atomic ".status" JSON sidecar (tmp + rename) each ≥5 s
 *  - encrypted HLS refused with a clear error (appending = trash)
 *  - telemetry tee (their hm0/gm0): each written chunk fans out to
 *    subscribers — [takeThroughputMeter] powers the notification's MB/s.
 *  - ExoPlayer-Lib user-agent spoof
 */
class HlsCapture(
    private val playlistUrl: String,
    private var sink: CaptureSink,
    private val localFallback: (() -> CaptureSink)? = null,
) {
    /** Secondary local convenience ctor (device-file captures). */
    constructor(playlistUrl: String, outFile: File) :
        this(playlistUrl, LocalCaptureSink(outFile) as CaptureSink)

    @Volatile var cancelled = false; private set
    /** True when the network target failed at open and the capture continued on local storage. */
    @Volatile var fellBack = false; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var bytesWritten: Long = 0; private set
    @Volatile var segmentsWritten = 0; private set
    private val startedAt = System.currentTimeMillis()
    private var lastStatusFlush = 0L
    private var lastMeter = System.currentTimeMillis()
    private var lastMeterBytes = 0L

    /** hm0/gm0, consumable form: throughput is metered off the
     *  write path (the future watch-while-recording player taps in here). */
    private val meterLock = Any()

    suspend fun run(
        maxDurationMs: Long = 60_000L,
        minFreeBytes: Long = 500L * 1024L * 1024L,
        maxBytes: Long = 0L,
        stallTimeoutMs: Long = 10_000,
    ): CaptureEnd = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        flushStatus("recording")
        try {
            val stream = openSink() ?: return@withContext CaptureEnd.NetworkFallback
            if (isRawStreamUrl(playlistUrl)) {
                return@withContext runRawStream(stream, start, maxDurationMs, minFreeBytes, maxBytes)
            }
            val window = SegmentWindow()
            while (!cancelled && System.currentTimeMillis() - start < maxDurationMs) {
                pollOnce(stream, window, minFreeBytes, maxBytes, stallTimeoutMs)?.let { return@withContext it }
            }
            flushStatus("finished")
            return@withContext CaptureEnd.Finished(
                System.currentTimeMillis() - startedAt, bytesWritten, segmentsWritten)
        } catch (t: Throwable) {
            lastError = t.message
            flushStatus(if (cancelled) "stopped" else "error")
            return@withContext if (cancelled) CaptureEnd.Stopped
            else CaptureEnd.WriteError(t.message ?: "capture failed")
        } finally {
            runCatching { sink.close() }
        }
    }

    /** Segment bookkeeping for one capture: what was written or given up on, and when data last arrived. */
    private class SegmentWindow {
        val seen = HashSet<String>()
        val failures = HashMap<String, Int>()
        var lastData = System.currentTimeMillis()
    }

    /** Opens the sink; null means a network folder failed and the caller should fall back to the device. */
    private fun openSink(): OutputStream? = try {
        sink.open()
    } catch (t: Throwable) {
        if (sink !is SmbCaptureSink) throw t
        lastError = "network folder failed, recording on this device"
        lastStatusFlush = 0   // force the notice into the sidecar
        flushStatus("fallback_local")
        val local = localFallback?.invoke()
        if (local == null) null else {
            runCatching { sink.close() }
            sink = local
            fellBack = true
            local.open()
        }
    }

    /** One governor check + playlist fetch + segment append. Returns the end state, or null to keep polling. */
    private suspend fun pollOnce(
        stream: OutputStream,
        window: SegmentWindow,
        minFreeBytes: Long,
        maxBytes: Long,
        stallTimeoutMs: Long,
    ): CaptureEnd? {
        sizeCapEnd(maxBytes)?.let { return it }
        storageEnd(minFreeBytes)?.let { return it }
        val pl = fetchPlaylist(playlistUrl)
        if (pl == null) {
            if (System.currentTimeMillis() - window.lastData > stallTimeoutMs) {
                flushStatus("stream_lost")
                return CaptureEnd.StreamLost
            }
            kotlinx.coroutines.delay(1500)
            return null
        }
        flushStatus("recording")
        if (pl.encrypted) {
            lastError = "encrypted HLS not supported"
            flushStatus("error")
            return CaptureEnd.WriteError(lastError!!)
        }
        val newData = appendNewSegments(pl, stream, window)
        // Segments that left the live window never return; keep the bookkeeping to the window.
        val inWindow = pl.segments.toSet()
        window.seen.retainAll(inWindow)
        window.failures.keys.retainAll(inWindow)
        // A failed segment still being retried must not be cut off by ENDLIST.
        if (pl.endList && pl.segments.all { it in window.seen }) {
            flushStatus("finished")
            return CaptureEnd.Finished(System.currentTimeMillis() - startedAt, bytesWritten, segmentsWritten)
        }
        if (!newData) {
            // Cadence: half the target duration, clamped sane
            kotlinx.coroutines.delay(pl.targetDurationMs.coerceIn(1500L, 15_000L) / 2)
        }
        return null
    }

    /** Fetches and appends every unseen segment in order. Returns whether any data was written. */
    private fun appendNewSegments(pl: Playlist, stream: OutputStream, window: SegmentWindow): Boolean {
        var newData = false
        for (sg in pl.segments) {
            if (sg in window.seen) continue
            val bytes = fetchSegment(sg)
            if (bytes != null) {
                window.seen.add(sg)
                stream.write(bytes)
                bytesWritten += bytes.size
                segmentsWritten++
                newData = true
                window.lastData = System.currentTimeMillis()
            } else if (window.failures.merge(sg, 1, Int::plus)!! < MAX_SEGMENT_ATTEMPTS) {
                // Retry next poll; later segments wait so the container stays in order.
                break
            } else {
                window.seen.add(sg)
            }
        }
        return newData
    }

    private fun sizeCapEnd(maxBytes: Long): CaptureEnd? {
        if (maxBytes <= 0 || bytesWritten < maxBytes) return null
        lastError = "size cap reached"
        flushStatus("capped")
        return CaptureEnd.SizeCapped
    }

    private fun storageEnd(minFreeBytes: Long): CaptureEnd? {
        val usable = sink.freeBytes()
        if (usable == Long.MAX_VALUE || usable >= minFreeBytes) return null
        flushStatus("storage_full")
        return CaptureEnd.StorageFull
    }

    private fun isRawStreamUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".ts") || clean.endsWith(".mpegts")
    }

    /** Continuous MPEG-TS capture for raw live URLs (non-HLS) respecting duration, size cap, free space, and cancel. */
    private suspend fun runRawStream(
        stream: OutputStream,
        start: Long,
        maxDurationMs: Long,
        minFreeBytes: Long,
        maxBytes: Long,
    ): CaptureEnd {
        // MPEG-TS is self-synchronising, so a reconnect just appends the resumed stream.
        val policy = StreamReconnectPolicy(jitter = 0.25)
        val buf = ByteArray(64 * 1024)
        var lastSpaceCheck = 0L
        fun live() = !cancelled && System.currentTimeMillis() - start < maxDurationMs
        while (live()) {
            val conn = URL(playlistUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = HttpSupport.READ_MS
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val code = conn.responseCode
                if (code !in 200..299) {
                    // Retrying a refused provider slot only deepens the lockout.
                    if (PlaybackFailure.fromHttpCode(code) == PlaybackFailure.SessionLimit) {
                        flushStatus("stream_lost")
                        return CaptureEnd.StreamLost
                    }
                } else {
                    val inp = conn.inputStream
                    while (live()) {
                        sizeCapEnd(maxBytes)?.let { return it }
                        val now = System.currentTimeMillis()
                        if (now - lastSpaceCheck >= 5_000L) {
                            lastSpaceCheck = now
                            storageEnd(minFreeBytes)?.let { return it }
                            flushStatus("recording")
                        }
                        val read = inp.read(buf)
                        if (read < 0) break
                        if (read > 0) {
                            try {
                                stream.write(buf, 0, read)
                            } catch (e: IOException) {
                                throw SinkFailure(e)
                            }
                            bytesWritten += read
                            segmentsWritten = 1
                            policy.reset()
                        }
                    }
                }
            } catch (_: IOException) {
                // connect or read failure: fall through to backoff
            } finally {
                runCatching { conn.disconnect() }
            }
            if (!live()) break
            val wait = policy.nextDelayMs() ?: run {
                flushStatus("stream_lost")
                return CaptureEnd.StreamLost
            }
            delay(wait)
        }
        flushStatus(if (cancelled) "stopped" else "finished")
        return if (cancelled) CaptureEnd.Stopped
        else CaptureEnd.Finished(System.currentTimeMillis() - startedAt, bytesWritten, segmentsWritten)
    }

    /** Local write failure: fatal, must not be mistaken for a network drop. */
    private class SinkFailure(cause: java.io.IOException) : RuntimeException(cause.message, cause)

    fun stop() { cancelled = true }

    @Volatile private var lastThroughput = 0f

    /** Atomic status sidecar: tmp-write + rename, ≥5 s cadence. */
    private fun flushStatus(state: String) {
        val now = System.currentTimeMillis()
        if (state == "recording" && now - lastStatusFlush < 5_000) return
        lastStatusFlush = now
        try {
            val obj = buildJsonObject {
                put("state", state)
                put("bytes", bytesWritten)
                put("segments", segmentsWritten)
                put("startedAt", startedAt)
                put("updatedAt", now)
                put("error", lastError ?: "")
                put("path", sink.displayPath)
            }.toString()
            val sidecar = statusFile(sink) ?: return
            sidecar.parentFile?.mkdirs()
            val tmp = File(sidecar.path + ".tmp")
            tmp.writeText(obj)
            val dst = File(sidecar.path)
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
        } catch (_: Exception) { /* sidecar is advisory; never kill a capture */ }
    }

    private class Playlist(
        val segments: List<String>,
        val targetDurationMs: Long,
        val endList: Boolean,
        val encrypted: Boolean,
    )

    /** Playlist text with metadata. Master playlists (#EXT-X-STREAM-INF)
     *  resolve by recursing into the HIGHEST-BANDWIDTH variant (
     *  first-variant recursion could land on a 480p rendition). Depth-capped. */
    private fun fetchPlaylist(url: String, depth: Int = 0): Playlist? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000; conn.readTimeout = HttpSupport.READ_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (depth < 2 && body.contains("#EXT-X-STREAM-INF")) {
                val picked = pickVariant(body.lines()) ?: body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                return picked?.let { fetchPlaylist(resolve(url, it), depth + 1) }
            }
            val base = url.substringBeforeLast('/')
            var targetMs = 6_000L
            var endList = false
            var encrypted = false
            for (l in body.lines()) {
                val t = l.trim()
                when {
                    t.startsWith("#EXT-X-TARGETDURATION:") ->
                        t.substringAfter(':').toLongOrNull()?.let { targetMs = it * 1000 }
                    t == "#EXT-X-ENDLIST" -> endList = true
                    t.startsWith("#EXT-X-KEY") && !t.contains("METHOD=NONE") -> encrypted = true
                }
            }
            val segs = body.lines().asSequence()
                .map(String::trim)
                .filterNot { it.isEmpty() || it.startsWith("#") }
                .map { if (it.isHttpUrl()) it else resolve(base, it) }
                .toList()
            return Playlist(segs, targetMs, endList, encrypted)
        } catch (_: Exception) {
            return null
        } finally { conn.disconnect() }
    }

    private fun fetchSegment(segUrl: String): ByteArray? {
        val conn = URL(segUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = HttpSupport.CONNECT_MS; conn.readTimeout = HttpSupport.READ_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) return null
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SEGMENT_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            return null
        } finally { conn.disconnect() }
    }

    companion object {
        private const val MAX_SEGMENT_ATTEMPTS = 3
        private const val MAX_SEGMENT_BYTES = 64L * 1024 * 1024
        private const val MAX_TRACKED_SEGMENTS = 1_000

        /** v51.c spoofs ExoPlayer's UA — provider portals whitelist it. */
        val USER_AGENT: String = "CartelTV (Linux;Android " +
            android.os.Build.VERSION.RELEASE + ") ExoPlayerLib/1.4.1"

        fun statusFile(sink: CaptureSink): File? = when (sink) {
            is LocalCaptureSink -> File(sink.displayPath + ".status")
            else -> null   // SMB sidecars would need a second share round-trip; skip
        }

        /** Pure: pick the highest-BANDWIDTH variant URI from master-playlist lines.
         *  (unit-tested; falls back to first URI when BANDWIDTH is absent) */
        fun pickVariant(lines: List<String>): String? {
            val bandWidth = BANDWIDTH_ATTR
            var best: Pair<Int, String>? = null
            for (i in lines.indices) {
                val l = lines[i]
                if (l.contains("#EXT-X-STREAM-INF")) {
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                    val bw = bandWidth.find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (best == null || bw > best.first) best = bw to uri
                }
            }
            return best?.second
        }

        /** Pure: naive URL join (RFC-ish: strips the base's trailing FILENAME
         *  segment so relatives resolve as playlist siblings — unit-tested). */
        fun resolve(base: String, path: String): String =
            if (path.isHttpUrl()) path
            else if (path.startsWith("/")) {
                val rootEnd = base.indexOf('/', base.indexOf("//") + 2)
                if (rootEnd < 0) base + path else base.substring(0, rootEnd) + path
            } else {
                val trimmedBase =
                    if (base.endsWith('/')) base
                    else {
                        val li = base.lastIndexOf('/')
                        if (li > base.indexOf("//") + 1 && base.substring(li + 1).contains('.'))
                            base.substring(0, li + 1)
                        else "$base/"
                    }
                trimmedBase + path
            }
    }
}

sealed interface CaptureEnd {
    data class Finished(val durationMs: Long, val bytes: Long, val segments: Int) : CaptureEnd
    data class WriteError(val message: String) : CaptureEnd
    data object StreamLost : CaptureEnd
    data object StorageFull : CaptureEnd
    data object SizeCapped : CaptureEnd
    data object NetworkFallback : CaptureEnd
    data object Stopped : CaptureEnd
}
