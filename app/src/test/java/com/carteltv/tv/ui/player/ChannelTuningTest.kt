package com.carteltv.tv.ui.player

import com.carteltv.tv.data.xtream.XtreamClient.LiveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTuningTest {
    private fun ch(id: Long, name: String) = LiveStream(num = 0, name = name, streamId = id)
    private fun ch(id: Long, num: Int) = LiveStream(num = num, name = "Ch $id", streamId = id)

    @Test
    fun neighbour_skips_quality_copies_of_the_same_channel() {
        val names = listOf("CNN", "CNN HD", "BBC", "BBC FHD")
        assertEquals(2, ChannelTuning.neighbour(names, 0, 1))
        assertEquals(3, ChannelTuning.neighbour(names, 0, -1))
    }

    @Test
    fun index_is_reused_while_the_channel_list_is_unchanged() {
        val pool = listOf(ch(1, "CNN"), ch(2, "BBC"))
        assertSame(ChannelTuning.index(pool), ChannelTuning.index(pool))
    }

    @Test
    fun index_is_rebuilt_when_the_channel_list_changes() {
        val first = ChannelTuning.index(listOf(ch(1, "CNN")))
        val second = ChannelTuning.index(listOf(ch(2, "BBC")))
        assertNotSame(first, second)
        assertEquals(0, second.positions[2L])
        assertNull(second.positions[1L])
    }

    @Test
    fun digits_build_a_number_and_the_fourth_finishes_it() {
        val e = NumberEntry()
        assertFalse(e.press(4))
        assertFalse(e.press(8))
        assertFalse(e.press(2))
        assertEquals("482", e.text)
        assertTrue(e.press(1))
        assertEquals(4821, e.take())
        assertFalse(e.active)
    }

    @Test
    fun a_fifth_digit_starts_a_new_number() {
        val e = NumberEntry()
        listOf(1, 2, 3, 4).forEach { e.press(it) }
        e.press(9)
        assertEquals("9", e.text)
    }

    @Test
    fun taking_nothing_gives_nothing() {
        assertNull(NumberEntry().take())
    }

    @Test
    fun channels_are_found_by_the_providers_number() {
        val pool = listOf(ch(10, 101), ch(11, 205), ch(12, 482))
        assertEquals(12L, channelByNumber(pool, 482)?.streamId)
        assertNull(channelByNumber(pool, 3))
        assertEquals(205, channelNumberOf(pool, pool[1]))
    }

    @Test
    fun a_provider_that_numbers_nothing_is_numbered_by_list_position() {
        val pool = listOf(ch(10, 0), ch(11, 0), ch(12, 0))
        assertEquals(11L, channelByNumber(pool, 2)?.streamId)
        assertNull(channelByNumber(pool, 4))
        assertEquals(3, channelNumberOf(pool, pool[2]))
    }

    @Test
    fun duplicate_provider_numbers_keep_the_first_channel() {
        val pool = listOf(ch(10, 101), ch(11, 101), ch(12, 205))
        assertEquals(10L, channelByNumber(pool, 101)?.streamId)
    }

    @Test
    fun a_new_channel_list_rebuilds_the_number_index() {
        val first = listOf(ch(10, 101))
        val second = listOf(ch(20, 101))
        assertEquals(10L, channelByNumber(first, 101)?.streamId)
        assertEquals(20L, channelByNumber(second, 101)?.streamId)
    }

    @Test
    fun last_channel_is_the_newest_recent_that_is_not_the_one_playing() {
        assertEquals(7L, previousLiveId(listOf(9L, 7L, 5L), current = 9L))
        assertEquals(9L, previousLiveId(listOf(7L, 9L), current = 7L))
        assertNull(previousLiveId(listOf(9L), current = 9L))
        assertNull(previousLiveId(emptyList(), current = 9L))
    }

    @Test
    fun programme_progress_is_clamped_and_safe_with_missing_times() {
        assertEquals(0.5f, programmeProgress(1_500, 1_000, 2_000), 0.001f)
        assertEquals(0f, programmeProgress(500, 1_000, 2_000), 0f)
        assertEquals(1f, programmeProgress(9_000, 1_000, 2_000), 0f)
        assertEquals(0f, programmeProgress(1_500, 0, 2_000), 0f)
        assertEquals(0f, programmeProgress(1_500, 2_000, 1_000), 0f)
    }
}
