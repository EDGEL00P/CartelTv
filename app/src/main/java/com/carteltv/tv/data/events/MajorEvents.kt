package com.carteltv.tv.data.events

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Where each guide lists an event: a channel name and the guide's region code. */
@Serializable
data class MajorEventChannel(val name: String, val region: String = "")

/**
 * One big upcoming event from `events.json`, which the scheduled guide job builds from TV guides,
 * ESPN's schedule and Kayo's fixtures (see tools/big_events.py). Times are UTC epoch seconds.
 */
@Serializable
data class MajorEvent(
    val id: String,
    val title: String,
    val sport: String,
    val weight: Int = 0,
    val start: Long,
    val end: Long = 0,
    val channels: List<MajorEventChannel> = emptyList(),
    val regions: List<String> = emptyList(),
    val streams: List<String> = emptyList(),
) {
    val startMs: Long get() = start * 1000
    val endMs: Long get() = (if (end > start) end else start + DEFAULT_LENGTH_S) * 1000

    fun isLive(nowMs: Long): Boolean = nowMs in startMs..endMs

    /** Where to watch, streaming services first, at most [limit] names. */
    fun watchOn(limit: Int = 3): List<String> =
        (streams + channels.map { it.name }).distinct().take(limit)

    private companion object {
        const val DEFAULT_LENGTH_S = 3 * 3600L
    }
}

@Serializable
private data class MajorEventsDoc(val generated: Long = 0, val events: List<MajorEvent> = emptyList())

object MajorEvents {
    /** The rolling release the epg workflow publishes every six hours. */
    const val URL = "https://github.com/EDGEL00P/CartelTv/releases/download/epg-latest/events.json"

    private const val SOON_MS = 7 * 24 * 3_600_000L

    fun parse(text: String): List<MajorEvent> = HttpSupport.json.decodeFromString<MajorEventsDoc>(text).events

    suspend fun fetch(url: String = URL): List<MajorEvent> = withContext(Dispatchers.IO) { parse(HttpSupport.get(url)) }

    /**
     * What the hero should not let you miss: events that are live or still ahead, the ones inside the
     * next week first (soonest first), then anything further out by importance.
     */
    fun picks(events: List<MajorEvent>, nowMs: Long, limit: Int = 3): List<MajorEvent> =
        events
            .filter { it.endMs >= nowMs }
            .sortedWith(
                compareBy<MajorEvent> { it.startMs > nowMs + SOON_MS }
                    .thenBy { if (it.startMs > nowMs + SOON_MS) -it.weight else 0 }
                    .thenBy { it.startMs },
            )
            .take(limit)
}
