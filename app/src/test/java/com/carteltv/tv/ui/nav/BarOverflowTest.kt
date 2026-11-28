package com.carteltv.tv.ui.nav

import com.carteltv.tv.nav.AppSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarOverflowTest {
    private val all = listOf(AppSection.SEARCH, AppSection.WEATHER, AppSection.SETUP)

    @Test fun nothingIsPromotedBeforeEnoughVisits() {
        assertNull(promotedOverflow(all, mapOf("WEATHER" to 4)))
    }

    @Test fun theMostUsedSectionMovesOntoTheBar() {
        assertEquals(AppSection.WEATHER, promotedOverflow(all, mapOf("WEATHER" to 9, "SETUP" to 7)))
    }

    @Test fun withoutHistoryNothingIsPromoted() {
        assertNull(promotedOverflow(all, emptyMap()))
    }
}
