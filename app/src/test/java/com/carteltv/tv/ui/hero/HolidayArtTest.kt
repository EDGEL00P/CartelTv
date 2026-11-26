package com.carteltv.tv.ui.hero

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.toArgb
import com.carteltv.tv.R
import com.carteltv.tv.data.events.HolidayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidayArtTest {
    @Test fun everyHolidayResolvesToARealDrawable() {
        val photos = HolidayKind.entries.associateWith(::holidayPhoto)

        assertTrue(photos.values.all { it != 0 })
        assertEquals(10, photos.values.distinct().size)
        assertNotEquals(R.drawable.wx_snow, photos.getValue(HolidayKind.BOXING_DAY))
        assertEquals(
            photos.getValue(HolidayKind.NEW_YEAR),
            photos.getValue(HolidayKind.NEW_YEARS_EVE),
        )
        assertEquals(
            photos.getValue(HolidayKind.NEW_YEAR),
            photos.getValue(HolidayKind.NATIONAL_DAY),
        )
    }

    @Test fun everyHolidayCarriesASeasonalWashExceptRemembrance() {
        val washes = HolidayKind.entries.associateWith(::holidayWash)

        assertEquals(AndroidColor.WHITE, washes.getValue(HolidayKind.REMEMBRANCE).toArgb())
        assertTrue(washes.values.none { it.alpha != 1f })
        assertNotEquals(washes.getValue(HolidayKind.CHRISTMAS), washes.getValue(HolidayKind.ST_PATRICK))
        assertNotEquals(washes.getValue(HolidayKind.HALLOWEEN), washes.getValue(HolidayKind.NEW_YEAR))
        assertEquals(
            washes.getValue(HolidayKind.NEW_YEAR),
            washes.getValue(HolidayKind.NATIONAL_DAY),
        )
    }
}
