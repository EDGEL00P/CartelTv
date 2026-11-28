package com.carteltv.tv.data.espn

import androidx.compose.ui.graphics.Color
import com.carteltv.tv.ui.sports.clockLabel
import com.carteltv.tv.ui.sports.parseTeamColor
import com.carteltv.tv.ui.sports.plateColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamColorsTest {
    private val fallback = Color(0xFF2F7BFF)

    private fun score(period: Int = 0, clock: String? = null, shortDetail: String? = null, detail: String = "") =
        EspnScoreboardClient.Score(
            name = "B at A", detail = detail, state = "in", logoA = null, abbrA = "A", scoreA = "0",
            logoB = null, abbrB = "B", scoreB = "0", period = period, clock = clock, shortDetail = shortDetail,
        )

    @Test fun parsesHexWithOrWithoutHashAndRejectsAnythingElse() {
        assertEquals(Color(0xFFFB4F14), parseTeamColor("fb4f14"))
        assertEquals(Color(0xFFFB4F14), parseTeamColor("#FB4F14"))
        assertNull(parseTeamColor(null))
        assertNull(parseTeamColor("fff"))
        assertNull(parseTeamColor("zzzzzz"))
        assertNull(parseTeamColor(""))
    }

    @Test fun aBrandColourThatReadsWellIsUsedAsIs() {
        val plate = plateColors("9e1b32", "ffffff", fallback)
        assertEquals(Color(0xFF9E1B32), plate.outer)
    }

    @Test fun aBlackBrandColourUsesTheAlternateWhenThatIsReadable() {
        assertEquals(Color(0xFFDCA71D), plateColors("000000", "dca71d", fallback).outer)
    }

    @Test fun aBlackBrandColourWithNoUsableAlternateIsLiftedSoItDoesNotVanish() {
        val outer = plateColors("000000", "000000", fallback).outer
        assertNotEquals(Color(0xFF000000), outer)
        assertTrue(outer.red > 0.2f)
    }

    @Test fun aWhiteBrandColourIsDarkenedInsteadOfGlaring() {
        val outer = plateColors("ffffff", null, fallback).outer
        assertTrue(outer.red < 0.7f)
    }

    @Test fun missingColoursFallBackToTheThemeAccent() {
        assertEquals(fallback, plateColors(null, null, fallback).outer)
    }

    @Test fun theInnerEndIsDarkerThanTheOuterEnd() {
        val plate = plateColors("fb4f14", "000000", fallback)
        assertTrue(plate.inner.red < plate.outer.red && plate.inner.green < plate.outer.green)
    }

    @Test fun periodSportsShowPeriodAndClock() {
        assertEquals("2ND 9:33", clockLabel(score(period = 2, clock = "9:33", shortDetail = "9:33 - 2nd")))
        assertEquals("4TH 0:07", clockLabel(score(period = 4, clock = "0:07")))
    }

    @Test fun soccerKeepsTheFeedsOwnMinute() {
        assertEquals("85'", clockLabel(score(period = 2, clock = "85'", shortDetail = "85'")))
    }

    @Test fun nonClockStatusesAreUppercasedFromTheFeed() {
        assertEquals("HALFTIME", clockLabel(score(period = 2, clock = "0:00", shortDetail = "Halftime")))
        assertEquals("END OF 1ST", clockLabel(score(period = 1, clock = null, shortDetail = "End of 1st")))
    }
}