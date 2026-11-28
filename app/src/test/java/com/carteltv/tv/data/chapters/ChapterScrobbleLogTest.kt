package com.carteltv.tv.data.chapters

import com.carteltv.tv.data.chapters.ChapterScrobbleStore.ChapterState
import com.carteltv.tv.data.chapters.ChapterScrobbleStore.ItemChapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterScrobbleLogTest {

    private val item = ItemChapters(
        refId = "series:Foo S01E01",
        durationMs = 1_440_000,
        chapterCount = 4,
        chapters = mapOf(
            0 to ChapterState(0, completions = 2, lastCompletedAtMs = 1_000, partialMs = 0),
            1 to ChapterState(1, completions = 0, lastCompletedAtMs = 0, partialMs = 45_000),
        ),
        lastChapterIndex = 1,
        lastWatchedMs = 2_000,
    )

    @Test fun roundTripKeepsAllFields() {
        val parsed = ChapterScrobbleLog.parse(ChapterScrobbleLog.serialize(mapOf(item.refId to item)))
        assertEquals(mapOf(item.refId to item), parsed)
    }

    @Test fun parseRejectsGarbage() {
        assertEquals(emptyMap<String, ItemChapters>(), ChapterScrobbleLog.parse("not json"))
        assertEquals(emptyMap<String, ItemChapters>(), ChapterScrobbleLog.parse("[]"))
    }

    @Test fun parseDropsEntriesWithoutRef() {
        val raw = """[{"dur":100,"n":2,"ch":[]}]"""
        assertEquals(emptyMap<String, ItemChapters>(), ChapterScrobbleLog.parse(raw))
    }

    @Test fun parseDropsNegativeChapterIndicesAndClampsValues() {
        val raw = """[{"ref":"x","dur":-5,"n":-1,"last":-1,"seen":-9,"ch":[
            {"i":-3,"c":-2,"at":5,"p":-7},{"i":2,"c":1,"at":0,"p":10}]}]"""
        val parsed = ChapterScrobbleLog.parse(raw).values.single()
        assertEquals(0, parsed.durationMs)
        assertEquals(0, parsed.chapterCount)
        assertEquals(setOf(2), parsed.chapters.keys)
        assertEquals(10, parsed.chapters[2]!!.partialMs)
        assertEquals(1, parsed.chapters[2]!!.completions)
    }

    @Test fun serializeOrdersChaptersByIndexAndItemsByAge() {
        val older = item.copy(refId = "old", lastWatchedMs = 1)
        val json = ChapterScrobbleLog.serialize(mapOf(item.refId to item, "old" to older))
        val refs = Regex("\"ref\":\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(listOf("old", item.refId), refs)
    }

    @Test fun pruneKeepsActiveHistoryAndRecentOrphans() {
        val now = 100_000_000L
        val active = item.copy(refId = "in-history")
        val freshOrphan = item.copy(refId = "fresh", lastWatchedMs = now - 1000)
        val staleOrphan = item.copy(refId = "stale", lastWatchedMs = now - 40L * 24 * 3600 * 1000)
        val kept = ChapterScrobbleStore.prune(setOf("in-history"),
            mapOf("a" to active, "b" to freshOrphan, "c" to staleOrphan), now)
        assertEquals(setOf("a", "b"), kept.keys)
        assertTrue("stale" !in kept)
    }
}
