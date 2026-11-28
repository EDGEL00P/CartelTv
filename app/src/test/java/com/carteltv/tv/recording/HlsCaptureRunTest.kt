package com.carteltv.tv.recording

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class HlsCaptureRunTest {

    private class MemSink(var free: Long = Long.MAX_VALUE) : CaptureSink {
        val data = ByteArrayOutputStream()
        var closed = false
        override val displayPath = "mem://capture"
        override fun bytesSoFar() = data.size().toLong()
        override fun freeBytes() = free
        override fun open(): OutputStream = data
        override fun close() { closed = true }
    }

    private lateinit var server: ServerSocket
    private val polls = AtomicInteger()
    private lateinit var playlist: (poll: Int) -> String?
    private val segments = HashMap<String, ByteArray?>()

    private val base get() = "http://127.0.0.1:${server.localPort}"
    private fun seg(n: Int) = "$base/s$n.ts"

    @Before fun up() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    s.use { sock ->
                        val reader = sock.getInputStream().bufferedReader()
                        val path = URI(reader.readLine().split(' ')[1]).path
                        while (reader.readLine().orEmpty().isNotEmpty()) { /* drain headers */ }
                        val body: ByteArray? = if (path == "/list.m3u8") playlist(polls.incrementAndGet())?.toByteArray()
                            else segments[path]
                        val out = sock.getOutputStream()
                        if (body == null) out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        else {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                        out.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun down() { server.close() }

    private fun m3u8(vararg segs: Int, end: Boolean = false, extra: String = "") =
        "#EXTM3U\n#EXT-X-TARGETDURATION:2\n$extra" + segs.joinToString("") { "#EXTINF:2.0,\n${seg(it)}\n" } +
            if (end) "#EXT-X-ENDLIST\n" else ""

    private fun capture(sink: MemSink, maxBytes: Long = 0, maxMs: Long = 20_000, stallMs: Long = 10_000): CaptureEnd =
        runBlocking { HlsCapture("$base/list.m3u8", sink).run(maxDurationMs = maxMs, maxBytes = maxBytes, stallTimeoutMs = stallMs) }

    @Test fun endlistFinishesWithAllSegmentsInOrder() {
        (1..3).forEach { segments["/s$it.ts"] = ByteArray(10) { _ -> it.toByte() } }
        playlist = { m3u8(1, 2, 3, end = true) }
        val sink = MemSink()
        val end = capture(sink)
        assertTrue(end is CaptureEnd.Finished)
        end as CaptureEnd.Finished
        assertEquals(3, end.segments)
        assertEquals(30L, end.bytes)
        assertArrayEquals(ByteArray(10) { 1 } + ByteArray(10) { 2 } + ByteArray(10) { 3 }, sink.data.toByteArray())
        assertTrue(sink.closed)
    }

    @Test fun rollingWindowWritesEachSegmentOnce() {
        (1..4).forEach { segments["/s$it.ts"] = byteArrayOf(it.toByte()) }
        playlist = { poll ->
            when (poll) {
                1 -> m3u8(1, 2)
                2 -> m3u8(2, 3)
                else -> m3u8(3, 4, end = true)
            }
        }
        val sink = MemSink()
        val end = capture(sink)
        assertTrue(end is CaptureEnd.Finished)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.data.toByteArray())
    }

    @Test fun sizeCapStopsCapture() {
        (1..3).forEach { segments["/s$it.ts"] = ByteArray(100) }
        playlist = { m3u8(1, 2, 3) }
        val end = capture(MemSink(), maxBytes = 100)
        assertEquals(CaptureEnd.SizeCapped, end)
    }

    @Test fun lowFreeSpaceHaltsBeforeWriting() {
        segments["/s1.ts"] = ByteArray(10)
        playlist = { m3u8(1, end = true) }
        val sink = MemSink(free = 1L)
        assertEquals(CaptureEnd.StorageFull, capture(sink))
        assertEquals(0, sink.data.size())
    }

    @Test fun missingPlaylistBecomesStreamLostAfterStall() {
        playlist = { null }
        assertEquals(CaptureEnd.StreamLost, capture(MemSink(), stallMs = 200))
    }

    @Test fun encryptedPlaylistIsRefused() {
        segments["/s1.ts"] = ByteArray(10)
        playlist = { m3u8(1, extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n") }
        val sink = MemSink()
        val end = capture(sink)
        assertTrue(end is CaptureEnd.WriteError)
        assertEquals(0, sink.data.size())
    }

    @Test fun segmentThatKeepsFailingIsSkippedAfterThreeAttempts() {
        segments["/s1.ts"] = null
        segments["/s2.ts"] = byteArrayOf(2)
        playlist = { m3u8(1, 2, end = true) }
        val sink = MemSink()
        val end = capture(sink)
        assertTrue(end is CaptureEnd.Finished)
        assertArrayEquals(byteArrayOf(2), sink.data.toByteArray())
    }

    @Test fun durationLimitEndsLivePlaylistAsFinished() {
        segments["/s1.ts"] = byteArrayOf(1)
        playlist = { m3u8(1) }
        val end = capture(MemSink(), maxMs = 800)
        assertTrue(end is CaptureEnd.Finished)
        assertEquals(1, (end as CaptureEnd.Finished).segments)
    }
}
