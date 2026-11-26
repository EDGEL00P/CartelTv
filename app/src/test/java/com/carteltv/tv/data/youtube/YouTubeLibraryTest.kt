package com.carteltv.tv.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeLibraryTest {

    private fun entry(id: String) = YouTubeLibrary.Entry(id, "title $id")
    private val same = { a: YouTubeLibrary.Entry, b: YouTubeLibrary.Entry -> a.id == b.id }

    @Test fun newestGoesFirstAndOldCopyIsRemoved() {
        val list = listOf(entry("a"), entry("b"), entry("c"))
        assertEquals(listOf("b", "a", "c"), YouTubeLibrary.front(list, entry("b"), 10, same).map { it.id })
    }

    @Test fun listIsTrimmedToCap() {
        val list = (1..5).map { entry("v$it") }
        val out = YouTubeLibrary.front(list, entry("new"), 3, same)
        assertEquals(listOf("new", "v1", "v2"), out.map { it.id })
    }

    @Test fun searchesDedupeIgnoringCaseAndTrimWhitespace() {
        var s = listOf<String>()
        s = YouTubeLibrary.frontSearch(s, "  Lofi Beats ")
        s = YouTubeLibrary.frontSearch(s, "news")
        s = YouTubeLibrary.frontSearch(s, "lofi beats")
        assertEquals(listOf("lofi beats", "news"), s)
    }

    @Test fun blankSearchIsIgnored() {
        assertEquals(listOf("a"), YouTubeLibrary.frontSearch(listOf("a"), "   "))
    }

    @Test fun searchHistoryStaysBounded() {
        var s = listOf<String>()
        repeat(40) { s = YouTubeLibrary.frontSearch(s, "q$it") }
        assertEquals(YouTubeLibrary.SEARCH_CAP, s.size)
        assertEquals("q39", s.first())
    }
}
