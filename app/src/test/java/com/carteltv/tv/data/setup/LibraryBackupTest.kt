package com.carteltv.tv.data.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryBackupTest {

    private val ref: (String) -> String = { it.substringBefore("|") }

    @Test fun roundTrip() {
        val text = LibraryBackup.encode(setOf("live:2", "live:1"), listOf("a|A", "b|B"))
        val snap = LibraryBackup.decode(text)
        assertEquals(listOf("live:1", "live:2"), snap.favs)
        assertEquals(listOf("a|A", "b|B"), snap.recents)
    }

    @Test fun mergeUnionsFavsAndKeepsDeviceRecentsFirst() {
        val backup = LibraryBackup.Snapshot(favs = listOf("live:2", "live:3"), recents = listOf("a|old", "c|C"))
        val m = LibraryBackup.merge(setOf("live:1", "live:2"), listOf("a|new", "b|B"), backup, ref)
        assertEquals(setOf("live:1", "live:2", "live:3"), m.favs)
        assertEquals(listOf("a|new", "b|B", "c|C"), m.recents)
    }

    @Test fun mergeCapsRecents() {
        val backup = LibraryBackup.Snapshot(recents = (1..50).map { "r$it|x" })
        assertEquals(30, LibraryBackup.merge(emptySet(), emptyList(), backup, ref).recents.size)
    }

    @Test fun rejectsGarbageAndNewerVersions() {
        assertThrows(IllegalArgumentException::class.java) { LibraryBackup.decode("not json") }
        assertThrows(IllegalArgumentException::class.java) { LibraryBackup.decode("""{"version":99}""") }
    }
}
