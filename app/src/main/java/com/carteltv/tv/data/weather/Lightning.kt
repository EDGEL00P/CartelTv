package com.carteltv.tv.data.weather
import com.carteltv.tv.core.suspendRunCatching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One detected discharge. */
data class Strike(val tMs: Long, val lat: Double, val lon: Double)

/**
 * Live lightning from the Blitzortung.org community detector network (public websocket, no key).
 * The public stream is a thinned, world-wide sample, so it is filtered client-side to [RETAIN_KM] around the
 * watched place and kept for an hour. Ref-counted: [start] when a screen needs it, [stop] when it leaves.
 */
object Lightning {
    enum class Link { OFF, CONNECTING, LIVE, RECONNECTING }

    const val RETAIN_KM = 400.0
    const val RETAIN_MS = 60 * 60_000L
    private const val CAP = 6000
    private val hosts = listOf("ws1", "ws7", "ws8")

    var link by mutableStateOf(Link.OFF)
        private set
    /** Ticks (throttled) whenever the strike list changes, so canvases and panels can observe it. */
    var version by mutableIntStateOf(0)
        private set
    var lastMessageMs by mutableStateOf(0L)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0
    private val strikes = ArrayDeque<Strike>()
    @Volatile private var watchLat = 0.0
    @Volatile private var watchLon = 0.0
    @Volatile private var lastBump = 0L

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val feedTimes = ArrayDeque<Long>()

    fun snapshot(): List<Strike> = synchronized(strikes) { strikes.toList() }

    /** Strikes the stream delivered worldwide in the last minute: the honest measure of how complete the feed is. */
    fun feedPerMin(nowMs: Long = System.currentTimeMillis()): Int =
        synchronized(feedTimes) { feedTimes.count { nowMs - it <= 60_000L } }

    fun start(lat: Double, lon: Double) {
        watch(lat, lon)
        synchronized(this) {
            users++
            if (job?.isActive == true) return
            job = scope.launch { runLoop() }
        }
    }

    fun watch(lat: Double, lon: Double) {
        if (kotlin.math.abs(lat - watchLat) > 0.5 || kotlin.math.abs(lon - watchLon) > 0.5) {
            synchronized(strikes) { strikes.clear() }
            version++
        }
        watchLat = lat
        watchLon = lon
    }

    fun stop() {
        synchronized(this) {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return
            job?.cancel()
            job = null
        }
        link = Link.OFF
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            if (!com.carteltv.tv.data.network.Connectivity.isOnline) {
                link = Link.RECONNECTING
                com.carteltv.tv.data.network.Connectivity.awaitOnline()
                attempt = 0
            }
            link = if (attempt == 0) Link.CONNECTING else Link.RECONNECTING
            val host = hosts[attempt % hosts.size]
            var opened = false
            suspendRunCatching {
                socketFlow(host).collect { ev ->
                    when (ev) {
                        is Ev.Open -> { opened = true; attempt = 0; link = Link.LIVE }
                        is Ev.Text -> ingest(ev.payload)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            link = Link.RECONNECTING
            if (!opened) attempt++
            delay(min(30_000L, 1_500L shl min(attempt, 4)))
        }
    }

    private sealed interface Ev {
        data object Open : Ev
        data class Text(val payload: String) : Ev
    }

    private fun socketFlow(host: String) = callbackFlow<Ev> {
        val ws = client.newWebSocket(
            Request.Builder().url("wss://$host.blitzortung.org/").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("{\"a\":111}")
                    trySend(Ev.Open)
                }
                override fun onMessage(webSocket: WebSocket, text: String) { trySend(Ev.Text(text)) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
            },
        )
        awaitClose { ws.cancel() }
    }

    private fun ingest(raw: String) {
        val s = runCatching {
            val o = HttpSupport.json.parseToJsonElement(decode(raw)).jsonObject
            val ns = o["time"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
            val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return
            val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return
            Strike(ns / 1_000_000L, lat, lon)
        }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        lastMessageMs = now
        synchronized(feedTimes) {
            feedTimes.addLast(now)
            while (feedTimes.isNotEmpty() && now - feedTimes.first() > 60_000L) feedTimes.removeFirst()
        }
        if (StormMath.km(watchLat, watchLon, s.lat, s.lon) > RETAIN_KM) return
        synchronized(strikes) {
            strikes.addLast(s)
            while (strikes.isNotEmpty() && (now - strikes.first().tMs > RETAIN_MS || strikes.size > CAP)) strikes.removeFirst()
        }
        if (now - lastBump > 400) { lastBump = now; version++ }
    }

    /** The feed is LZW-style compressed: codes below 256 are literals, higher ones index a rolling dictionary. */
    internal fun decode(b: String): String {
        if (b.isEmpty()) return b
        val dict = HashMap<Int, String>()
        var c = b[0].toString()
        var prev = c
        val out = StringBuilder(c)
        var next = 256
        for (i in 1 until b.length) {
            val code = b[i].code
            val cur = if (code < 256) b[i].toString() else dict[code] ?: (prev + c)
            out.append(cur)
            c = cur.substring(0, 1)
            dict[next++] = prev + c
            prev = cur
        }
        return out.toString()
    }
}

/** Great-circle helpers shared by the storm analytics and the map. */
object StormMath {
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial compass bearing, degrees clockwise from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
