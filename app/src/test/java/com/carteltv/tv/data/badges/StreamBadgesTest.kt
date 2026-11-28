package com.carteltv.tv.data.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The badge-matching contract: first filter wins, one badge per group, Nuvio #AARRGGBB colors. */
class StreamBadgesTest {

    private fun f(id: String, group: String, name: String, pattern: String, tag: String? = null) =
        StreamBadges.FilterDef(id = id, groupId = group, name = name, pattern = pattern, tagColor = tag)

    @Test
    fun `one badge per group - first match wins`() {
        val filters = listOf(
            f("1", "resolution", "4K", "(?i)\\b(4k|2160p)\\b"),
            f("2", "resolution", "HD", "(?i)\\bhd\\b"),          // same group: must lose to "1"
            f("3", "visual", "HDR10", "(?i)\\bhdr10\\b"),
        )
        val badges = StreamBadges.match(filters, "Movie 2023 2160p HDR10 WEBDL")
        assertEquals(2, badges.size)
        assertEquals("4K", badges[0].name)
        assertEquals("HDR10", badges[1].name)
    }

    @Test
    fun `case insensitive like nuvio`() {
        val filters = listOf(f("1", "audio", "ATMOS", "(?i)\\b(truehd|atmos)\\b"))
        assertEquals("ATMOS", StreamBadges.match(filters, "truedef SKIP trueHD 5.1").single().name)
    }

    @Test
    fun `empty or blank title gets nothing`() {
        val filters = listOf(f("1", "resolution", "4K", "(?i)4k"))
        assertTrue(StreamBadges.match(filters, null).isEmpty())
        assertTrue(StreamBadges.match(filters, "  ").isEmpty())
        assertTrue(StreamBadges.match(emptyList(), "2160p").isEmpty())
    }

    @Test
    fun `broken regex is skipped without killing the rest`() {
        val filters = listOf(
            f("1", "a", "Bad", "(?i)[unclosed"),
            f("2", "b", "Good", "(?i)hdr"),
        )
        assertEquals("Good", StreamBadges.match(filters, "HDR").single().name)
    }

    @Test
    fun `aar gg bb colors parse to argb ints`() {
        assertEquals(0xFF33FFFF.toInt(), StreamBadges.color("#FF33FFFF"))
        assertNull(StreamBadges.color(null))
        assertNull(StreamBadges.color("nope"))
    }
}
