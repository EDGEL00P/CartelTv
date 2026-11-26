package com.carteltv.tv.data.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MajorEventsTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun event(id: String, startOffsetMs: Long, weight: Int = 7, lengthMs: Long = 3 * hour) = MajorEvent(
        id = id, title = id, sport = "UFC", weight = weight,
        start = (now + startOffsetMs) / 1000, end = (now + startOffsetMs + lengthMs) / 1000,
    )

    @Test fun parsesTheDocumentTheJobWritesAndIgnoresFieldsItDoesNotKnow() {
        val json = """
            {"generated": 1795000000, "days": 28, "extra": true, "events": [
              {"id": "a1", "title": "UFC 335: Oliveira vs. Lopes", "sport": "UFC", "weight": 10,
               "start": 1796000000, "end": 1796010000,
               "channels": [{"name": "UFC - Ultimate Fighting Championship", "region": "US_SPORTS1"}],
               "regions": ["US_SPORTS1"], "streams": ["Paramount+"], "future": 1}
            ]}
        """.trimIndent()
        val e = MajorEvents.parse(json).single()
        assertEquals("UFC 335: Oliveira vs. Lopes", e.title)
        assertEquals(1_796_000_000_000L, e.startMs)
        assertEquals(listOf("Paramount+", "UFC - Ultimate Fighting Championship"), e.watchOn())
    }

    @Test fun anEventIsLiveBetweenItsStartAndEnd() {
        val e = event("e", startOffsetMs = -hour)
        assertTrue(e.isLive(now))
        assertFalse(event("later", startOffsetMs = hour).isLive(now))
        assertFalse(event("over", startOffsetMs = -5 * hour).isLive(now))
    }

    @Test fun anEventWithNoEndIsAssumedToRunThreeHours() {
        val e = MajorEvent(id = "x", title = "x", sport = "UFC", start = now / 1000)
        assertEquals(now + 3 * hour, e.endMs)
    }

    @Test fun watchOnListsServicesBeforeChannelsWithoutRepeats() {
        val e = MajorEvent(
            id = "x", title = "x", sport = "NFL", start = 1,
            channels = listOf(com.carteltv.tv.data.events.MajorEventChannel("Prime Video"), com.carteltv.tv.data.events.MajorEventChannel("Sky")),
            streams = listOf("Prime Video"),
        )
        assertEquals(listOf("Prime Video", "Sky"), e.watchOn())
        assertEquals(listOf("Prime Video"), e.watchOn(limit = 1))
    }

    @Test fun picksDropFinishedEventsAndPutThisWeekFirstSoonestFirst() {
        val events = listOf(
            event("finished", -10 * hour),
            event("farBig", 20 * day, weight = 10),
            event("thisWeekLate", 5 * day),
            event("liveNow", -hour),
            event("thisWeekSoon", 2 * day),
        )
        assertEquals(listOf("liveNow", "thisWeekSoon", "thisWeekLate"), MajorEvents.picks(events, now).map { it.id })
    }

    @Test fun beyondTheWeekTheMostImportantComesFirst() {
        val events = listOf(event("minor", 10 * day, weight = 6), event("major", 14 * day, weight = 10), event("mid", 8 * day, weight = 8))
        assertEquals(listOf("major", "mid", "minor"), MajorEvents.picks(events, now, limit = 3).map { it.id })
    }

    @Test fun picksAreCapped() {
        val events = List(8) { event("e$it", it * hour) }
        assertEquals(3, MajorEvents.picks(events, now).size)
        assertEquals(5, MajorEvents.picks(events, now, limit = 5).size)
    }
}
