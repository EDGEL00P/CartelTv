package com.carteltv.tv.data.xtream

import com.carteltv.tv.data.xtream.XtreamClient.LiveStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelCopiesTest {

    private fun s(id: Long, name: String, epg: String? = null) = LiveStream(streamId = id, name = name, epgId = epg)

    private val tsn = listOf(
        s(1, "CA| TSN 1 HD"), s(2, "CA| TSN 1 4K"), s(3, "CA| TSN 1 FHD"), s(4, "CA| TSN 1"), s(5, "CA| TSN 1 SD"),
    )

    @Test fun ranksBestFirst() {
        assertEquals(listOf(2L, 3L, 4L, 5L), ChannelDuplicates.copiesOf(tsn[0], tsn).map { it.streamId })
        assertEquals(listOf(2L, 3L, 1L, 4L, 5L), ChannelDuplicates.copiesOf(s(99, "CA| TSN 1"), tsn).map { it.streamId })
    }

    @Test fun differentNumbersRegionsAndCountriesAreNotCopies() {
        val pool = listOf(s(1, "CA| TSN 2 HD"), s(2, "CA| CTV East HD"), s(3, "US| TSN 1 HD"), s(4, "CA| CTV +1 HD"))
        assertEquals(emptyList<Long>(), ChannelDuplicates.copiesOf(s(10, "CA| TSN 1 HD"), pool).map { it.streamId })
        assertEquals(emptyList<Long>(), ChannelDuplicates.copiesOf(s(11, "CA| CTV West HD"), pool).map { it.streamId })
        assertEquals(emptyList<Long>(), ChannelDuplicates.copiesOf(s(12, "CA| CTV HD"), pool).map { it.streamId })
    }

    @Test fun sharedEpgIdIsNotEvidence() {
        val pool = listOf(s(1, "CA| Sportsnet 360", "same"))
        assertEquals(emptyList<Long>(), ChannelDuplicates.copiesOf(s(2, "CA| TSN 1 HD", "same"), pool).map { it.streamId })
    }

    @Test fun identityFoldsAccentsAndCase() {
        assertEquals(ChannelDuplicates.identity("CA| ICI Télé HD"), ChannelDuplicates.identity("CA| ici tele FHD"))
        assertNotEquals(ChannelDuplicates.identity("CA| TSN 1"), ChannelDuplicates.identity("CA| TSN 2"))
    }

    @Test fun failuresSinkACopyButNotAWholeTier() {
        val pool = listOf(s(1, "CA| TSN 1 4K"), s(2, "CA| TSN 1 FHD"))
        val ranked = ChannelDuplicates.copiesOf(s(3, "CA| TSN 1 HD"), pool) { if (it == 1L) 9 else 0 }
        assertEquals(listOf(1L, 2L), ranked.map { it.streamId })
    }

    @Test fun emptyNameHasNoCopies() {
        assertEquals(emptyList<Long>(), ChannelDuplicates.copiesOf(s(1, ""), listOf(s(2, ""))))
    }

    @Test fun failoverWalksCopiesOnceThenStops() {
        val f = StreamCopyFailover()
        val selected = tsn[2] // FHD
        assertEquals(2L, f.next(selected, selected.streamId, tsn, 0)?.streamId)
        assertEquals(1L, f.next(selected, 2L, tsn, 1_000)?.streamId)
        assertEquals(4L, f.next(selected, 1L, tsn, 2_000)?.streamId)
        assertEquals(5L, f.next(selected, 4L, tsn, 3_000)?.streamId)
        assertNull(f.next(selected, 5L, tsn, 4_000))
    }

    @Test fun failoverRestartsAfterWindow() {
        val f = StreamCopyFailover(windowMs = 1_000)
        val selected = tsn[2]
        f.next(selected, selected.streamId, listOf(selected, tsn[1]), 0)
        assertNull(f.next(selected, 2L, listOf(selected, tsn[1]), 500))
        assertEquals(2L, f.next(selected, selected.streamId, listOf(selected, tsn[1]), 5_000)?.streamId)
    }
}
