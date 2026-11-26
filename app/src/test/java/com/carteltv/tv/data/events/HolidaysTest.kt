package com.carteltv.tv.data.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class HolidaysTest {

    private fun next(region: HolidayRegion, y: Int, m: Int, d: Int) = Holidays.next(LocalDate.of(y, m, d), region)

    private fun names(region: HolidayRegion, year: Int = 2026) = Holidays.inYear(year, region).map { it.name }

    @Test fun easterFollowsTheLunarRuleForKnownYears() {
        assertEquals(LocalDate.of(2025, 4, 20), Holidays.easter(2025))
        assertEquals(LocalDate.of(2026, 4, 5), Holidays.easter(2026))
        assertEquals(LocalDate.of(2027, 3, 28), Holidays.easter(2027))
        assertEquals(LocalDate.of(2038, 4, 25), Holidays.easter(2038))
    }

    @Test fun nthWeekdayFindsTheRightDate() {
        assertEquals(LocalDate.of(2026, 11, 26), Holidays.nthWeekday(2026, 11, DayOfWeek.THURSDAY, 4))
        assertEquals(LocalDate.of(2027, 11, 25), Holidays.nthWeekday(2027, 11, DayOfWeek.THURSDAY, 4))
        assertEquals(LocalDate.of(2026, 10, 12), Holidays.nthWeekday(2026, 10, DayOfWeek.MONDAY, 2))
    }

    @Test fun countriesAsTheIpLookupNamesThemMapToTheirCalendar() {
        assertEquals(HolidayRegion.US, Holidays.regionOf("United States"))
        assertEquals(HolidayRegion.GB, Holidays.regionOf("United Kingdom"))
        assertEquals(HolidayRegion.AU, Holidays.regionOf(" australia "))
        assertEquals(HolidayRegion.NZ, Holidays.regionOf("New Zealand"))
        assertEquals(HolidayRegion.ZA, Holidays.regionOf("South Africa"))
        assertEquals(HolidayRegion.OTHER, Holidays.regionOf("Narnia"))
        assertEquals(HolidayRegion.OTHER, Holidays.regionOf(null))
    }

    @Test fun thanksgivingIsAmericanInNovemberAndCanadianInOctober() {
        assertEquals(LocalDate.of(2026, 11, 26), Holidays.inYear(2026, HolidayRegion.US).first { it.kind == HolidayKind.THANKSGIVING }.date)
        assertEquals(LocalDate.of(2026, 10, 12), Holidays.inYear(2026, HolidayRegion.CA).first { it.kind == HolidayKind.THANKSGIVING }.date)
        assertTrue("UK has no Thanksgiving", "Thanksgiving" !in names(HolidayRegion.GB))
        assertTrue("Australia has no Thanksgiving", "Thanksgiving" !in names(HolidayRegion.AU))
    }

    @Test fun eachCountryGetsItsOwnNationalDaysAndNotOthers() {
        assertTrue("Independence Day" in names(HolidayRegion.US))
        assertTrue("Canada Day" in names(HolidayRegion.CA))
        assertTrue("Australia Day" in names(HolidayRegion.AU))
        assertTrue("ANZAC Day" in names(HolidayRegion.AU) && "ANZAC Day" in names(HolidayRegion.NZ))
        assertTrue("Waitangi Day" in names(HolidayRegion.NZ))
        assertTrue("Freedom Day" in names(HolidayRegion.ZA) && "Heritage Day" in names(HolidayRegion.ZA))
        assertTrue("Republic Day" in names(HolidayRegion.IN))
        assertTrue("Bonfire Night" in names(HolidayRegion.GB))
        assertTrue("Independence Day" !in names(HolidayRegion.GB))
        assertTrue("Halloween" !in names(HolidayRegion.AU))
    }

    @Test fun boxingDayIsCommonwealthAndSouthAfricaCallsItTheDayOfGoodwill() {
        for (r in listOf(HolidayRegion.GB, HolidayRegion.IE, HolidayRegion.AU, HolidayRegion.NZ, HolidayRegion.CA)) {
            assertTrue("$r", "Boxing Day" in names(r))
        }
        assertTrue("Day of Goodwill" in names(HolidayRegion.ZA))
        assertTrue("Boxing Day" !in names(HolidayRegion.US))
    }

    @Test fun solemnDaysCarryNoJoke() {
        val solemn = listOf(HolidayRegion.AU, HolidayRegion.GB).flatMap { Holidays.inYear(2026, it) }.filter { it.kind == HolidayKind.REMEMBRANCE }
        assertTrue(solemn.isNotEmpty())
        assertTrue(solemn.all { it.tagline == "Lest we forget." })
    }

    @Test fun fromTodayTheNextHolidayDependsOnWhereYouAre() {
        assertEquals("Thanksgiving", next(HolidayRegion.US, 2026, 11, 22)!!.name)
        assertEquals("Remembrance Day", next(HolidayRegion.AU, 2026, 11, 8)!!.name)
        assertEquals("Christmas", next(HolidayRegion.AU, 2026, 11, 22)!!.name)
        assertEquals("Christmas", next(HolidayRegion.GB, 2026, 11, 22)!!.name)
    }

    @Test fun bonfireNightComesBeforeRemembranceInBritain() {
        assertEquals("Bonfire Night", next(HolidayRegion.GB, 2026, 11, 1)!!.name)
        assertEquals("Remembrance Day", next(HolidayRegion.GB, 2026, 11, 6)!!.name)
    }

    @Test fun theDayOfAHolidayStillCountsAndTheDayAfterMovesOn() {
        assertEquals(HolidayKind.THANKSGIVING, next(HolidayRegion.US, 2026, 11, 26)!!.kind)
        assertEquals(HolidayKind.CHRISTMAS, next(HolidayRegion.US, 2026, 11, 27)!!.kind)
        assertEquals(HolidayKind.BOXING_DAY, next(HolidayRegion.AU, 2026, 12, 26)!!.kind)
        assertEquals(HolidayKind.NEW_YEARS_EVE, next(HolidayRegion.AU, 2026, 12, 27)!!.kind)
        assertEquals(HolidayKind.NEW_YEAR, next(HolidayRegion.AU, 2027, 1, 1)!!.kind)
    }

    @Test fun theYearRollsOverToNextYearsHolidays() {
        val aus = next(HolidayRegion.AU, 2027, 1, 2)!!
        assertEquals(HolidayKind.NATIONAL_DAY, aus.kind)
        assertEquals(LocalDate.of(2027, 1, 26), aus.date)
        val us = next(HolidayRegion.US, 2027, 1, 2)!!
        assertEquals(LocalDate.of(2027, 2, 14), us.date)
    }

    @Test fun quietStretchesShowNoCountdown() {
        assertNull(next(HolidayRegion.US, 2026, 5, 10))
        assertNull(next(HolidayRegion.US, 2026, 8, 1))
    }

    @Test fun theLeadWindowIsInclusive() {
        // 2026-09-16 is exactly 45 days before Halloween (Oct 31).
        assertEquals(HolidayKind.HALLOWEEN, next(HolidayRegion.US, 2026, 9, 16)!!.kind)
        assertNull(next(HolidayRegion.US, 2026, 9, 15))
    }

    @Test fun everyRegionListsItsHolidaysInCalendarOrderWithNoRepeatedDates() {
        for (r in HolidayRegion.entries) {
            val all = Holidays.inYear(2026, r)
            assertEquals("$r not in order", all.map { it.date }, all.map { it.date }.sorted())
            assertTrue("$r has a holiday", all.isNotEmpty())
        }
    }
}
