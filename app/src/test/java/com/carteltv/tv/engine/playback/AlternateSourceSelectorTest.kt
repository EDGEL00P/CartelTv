package com.carteltv.tv.engine.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceFallbackTest {
    private val a = AlternateSource(infoHash = "a".repeat(40), sources = listOf("tracker:udp://x"))
    private val b = AlternateSource(url = "https://cdn.example/b.mkv")
    private val c = AlternateSource(infoHash = "c".repeat(40))

    @Test
    fun alternates_survive_the_intent_round_trip() {
        assertEquals(listOf(a, b), AlternateSourceSelector.decode(AlternateSourceSelector.encode(listOf(a, b))))
        assertEquals(emptyList<AlternateSource>(), AlternateSourceSelector.decode(null))
        assertEquals(emptyList<AlternateSource>(), AlternateSourceSelector.decode("not json"))
    }

    @Test
    fun the_first_source_that_resolves_wins_and_failed_ones_are_not_retried() = runBlocking {
        val tried = mutableListOf<String?>()
        val (link, rest) = AlternateSourceSelector.firstPlayable(listOf(a, b, c)) { tried += it.infoHash; null }
        assertEquals("https://cdn.example/b.mkv", link)
        assertEquals(listOf<String?>(a.infoHash), tried)
        assertEquals(listOf(c), rest)
    }

    @Test
    fun a_list_with_nothing_playable_runs_out() = runBlocking {
        val (link, rest) = AlternateSourceSelector.firstPlayable(listOf(a, c)) { null }
        assertNull(link)
        assertEquals(emptyList<AlternateSource>(), rest)
    }
}
