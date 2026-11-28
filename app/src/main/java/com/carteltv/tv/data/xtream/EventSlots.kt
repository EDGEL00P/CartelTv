package com.carteltv.tv.data.xtream

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Providers rotate live events through numbered slot channels and put the event in the channel name:
 * "US| MLS LIVE 15 [St. Louis vs. LAFC (2026-11-23 20:10:00)]". No XMLTV guide carries these, so the programme
 * is read from the name. The stamp is US Eastern; [ZONE] is America/New_York, so DST is honoured and the slots
 * start 0-35 minutes before kickoff. Names with no stamp ("[EVENT ONLY]") are empty slots and get no programme.
 */
object EventSlots {
    private val TIMED = Regex("""\[\s*([^\[\]]*?)\s*\((\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\)\s*]\s*$""")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val ZONE: ZoneId = com.carteltv.tv.data.espn.EspnDateMapper.EASTERN

    /** The names carry no end time, so a slot is assumed to run this long. */
    const val DEFAULT_MS = 3 * 60 * 60_000L

    data class Event(val title: String, val startMs: Long)

    fun parse(channelName: String): Event? {
        val m = TIMED.find(channelName) ?: return null
        val title = m.groupValues[1].replace('_', '/').trim()
        if (title.isEmpty()) return null
        val start = runCatching { LocalDateTime.parse(m.groupValues[2], STAMP).atZone(ZONE).toInstant().toEpochMilli() }
            .getOrNull() ?: return null
        return Event(title, start)
    }

    /** Slot channels whose event has not finished (by the assumed length), soonest start first: the live events schedule. */
    fun schedule(streams: List<XtreamClient.LiveStream>, nowMs: Long): List<XtreamClient.LiveStream> =
        streams.mapNotNull { s -> parse(s.name)?.takeIf { it.startMs + DEFAULT_MS > nowMs }?.let { s to it.startMs } }
            .sortedBy { it.second }
            .map { it.first }

    private val EMPTY = Regex("""(?i)\[\s*event only\s*]\s*$""")

    /** Slot-name keyword to the platform schedule channel `tools/streaming_guide.py` publishes (`Stream.<platform>.upcoming.cartel`). */
    private val PLATFORMS = listOf(
        "ESPN+" to "stream.espnplusus.upcoming.cartel",
        "PEACOCK" to "stream.peacockus.upcoming.cartel",
        "PARAMOUNT" to "stream.paramountplusus.upcoming.cartel",
        "VICTORY+" to "stream.victoryplusus.upcoming.cartel",
        "PRIME" to "stream.primevideous.upcoming.cartel",
        "APPLE" to "stream.appletvint.upcoming.cartel",
        "DAZN" to "stream.daznint.upcoming.cartel",
    )

    /** The platform schedule an idle slot belongs to, from its name; null when it is not a known streaming platform. */
    fun platformGuideId(channelName: String): String? {
        if (!EMPTY.containsMatchIn(channelName)) return null
        val up = GuideModel.cleanName(channelName).uppercase()
        return PLATFORMS.firstOrNull { up.startsWith(it.first) }?.second
    }

    /** Stream id to platform schedule id for every idle slot of a known platform, for the guide matcher. */
    fun platformAliases(channels: List<EpgMatcher.Channel>): Map<Long, String> =
        channels.mapNotNull { c -> if (!c.epgId.isNullOrBlank()) null else platformGuideId(c.name)?.let { c.streamId to it } }.toMap()

    /** An idle slot with nothing to show: no event and no platform schedule to fall back on, so lists leave it out. */
    fun isEmpty(channelName: String): Boolean = EMPTY.containsMatchIn(channelName) && platformGuideId(channelName) == null

    fun entry(event: Event) = XtreamClient.EpgEntry(
        title = event.title,
        description = null,
        startTs = (event.startMs / 1000L).toString(),
        stopTs = ((event.startMs + DEFAULT_MS) / 1000L).toString(),
    )
}
