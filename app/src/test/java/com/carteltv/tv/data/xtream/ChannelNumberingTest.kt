package com.carteltv.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNumberingTest {

    @Test
    fun first_assignment_numbers_in_list_order_from_one() {
        val numbers = ChannelNumbering.assign(emptyMap(), listOf(100L, 200L, 300L))
        assertEquals(mapOf(100L to 1, 200L to 2, 300L to 3), numbers)
    }

    @Test
    fun a_reorder_does_not_move_numbers() {
        val previous = mapOf(100L to 1, 200L to 2, 300L to 3)
        val numbers = ChannelNumbering.assign(previous, listOf(300L, 100L, 200L))
        assertEquals(3, numbers[300L])
        assertEquals(1, numbers[100L])
        assertEquals(2, numbers[200L])
    }

    @Test
    fun a_gone_channel_releases_its_number_to_the_next_new_one() {
        val previous = mapOf(100L to 1, 200L to 2)
        val numbers = ChannelNumbering.assign(previous, listOf(100L, 900L))
        assertEquals(2, numbers[900L])
        assertEquals(1, numbers[100L])
        assertNull(numbers[200L])
    }

    @Test
    fun the_lowest_free_number_is_given_first() {
        val previous = mapOf(100L to 1, 500L to 3)
        val numbers = ChannelNumbering.assign(previous, listOf(100L, 500L, 900L))
        assertEquals(2, numbers[900L])
    }

    @Test
    fun a_stale_duplicate_claim_renumbers_the_second_holder() {
        // A corrupt or hand-edited store can hold two ids on one number; the first stream in the
        // list keeps it, the other takes the lowest free number.
        val previous = mapOf(100L to 1, 200L to 1)
        val numbers = ChannelNumbering.assign(previous, listOf(100L, 200L))
        assertEquals(1, numbers[100L])
        assertTrue(numbers[200L]!! > 1)
    }

    @Test
    fun a_non_positive_claim_in_the_store_is_ignored() {
        val numbers = ChannelNumbering.assign(mapOf(100L to 0), listOf(100L, 200L))
        assertEquals(mapOf(100L to 1, 200L to 2), numbers)
    }

    @Test
    fun resolve_finds_the_owner_of_a_number() {
        val numbers = ChannelNumbering.assign(emptyMap(), listOf(100L, 200L))
        assertEquals(200L, ChannelNumbering.resolve(numbers, 2))
        assertNull(ChannelNumbering.resolve(numbers, 9))
    }

    @Test
    fun next_free_skips_every_number_in_use() {
        val numbers = mapOf(1L to 1, 2L to 2, 3L to 3)
        assertEquals(4, ChannelNumbering.nextFree(numbers))
        // An id excepted releases its number.
        assertEquals(2, ChannelNumbering.nextFree(numbers, except = 2L))
    }

    @Test
    fun encode_decode_round_trips() {
        val numbers = mapOf(305L to 1, 12L to 5)
        assertEquals(numbers, ChannelNumbering.decode(ChannelNumbering.encode(numbers)))
    }

    @Test
    fun decode_tolerates_junk_and_is_null_safe() {
        assertEquals(emptyMap<Long, Int>(), ChannelNumbering.decode(null))
        assertEquals(emptyMap<Long, Int>(), ChannelNumbering.decode(""))
        assertEquals(mapOf(12L to 5), ChannelNumbering.decode("junk;12=5;0=3;12=x;7=-1"))
    }
}
