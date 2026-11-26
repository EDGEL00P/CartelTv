package com.carteltv.tv.data.events
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal enum class HolidayKind {
    NEW_YEAR, VALENTINE, ST_PATRICK, EASTER, NATIONAL_DAY, HALLOWEEN, THANKSGIVING, CHRISTMAS,
    NEW_YEARS_EVE, BONFIRE_NIGHT, BOXING_DAY, REMEMBRANCE,
}

/** Which country's calendar to count down to; [OTHER] gets the holidays nearly everyone shares. */
internal enum class HolidayRegion { US, CA, GB, IE, AU, NZ, ZA, IN, OTHER }

internal data class Holiday(val kind: HolidayKind, val name: String, val date: LocalDate, val tagline: String)

/** The holidays the hero counts down to, by country, with the calendar rules that place them each year. */
internal object Holidays {
    /** How far ahead a holiday starts showing, so the countdown feels seasonal instead of permanent. */
    const val LEAD_DAYS = 45L

    private const val LEST_WE_FORGET = "Lest we forget."

    /** The calendar for a country as the IP lookup names it ("Australia", "United States", ...). */
    fun regionOf(country: String?): HolidayRegion = when (country?.trim()?.lowercase()) {
        "united states", "united states of america", "usa", "us" -> HolidayRegion.US
        "canada", "ca" -> HolidayRegion.CA
        "united kingdom", "uk", "gb", "great britain", "england", "scotland", "wales", "northern ireland" -> HolidayRegion.GB
        "ireland", "ie" -> HolidayRegion.IE
        "australia", "au" -> HolidayRegion.AU
        "new zealand", "nz" -> HolidayRegion.NZ
        "south africa", "za" -> HolidayRegion.ZA
        "india", "in" -> HolidayRegion.IN
        else -> HolidayRegion.OTHER
    }

    fun inYear(year: Int, region: HolidayRegion): List<Holiday> = buildList {
        fun add(kind: HolidayKind, name: String, date: LocalDate, tagline: String) = add(Holiday(kind, name, date, tagline))
        fun on(month: Int, day: Int) = LocalDate.of(year, month, day)
        val commonwealth = region in setOf(HolidayRegion.GB, HolidayRegion.IE, HolidayRegion.AU, HolidayRegion.NZ, HolidayRegion.CA, HolidayRegion.ZA)

        add(HolidayKind.NEW_YEAR, "New Year's Day", on(1, 1), "Resolutions start now. Probably.")
        if (region != HolidayRegion.IN) add(HolidayKind.VALENTINE, "Valentine's Day", on(2, 14), "Buy the flowers before 6 PM.")
        if (region in setOf(HolidayRegion.US, HolidayRegion.CA, HolidayRegion.IE)) {
            add(HolidayKind.ST_PATRICK, "St Patrick's Day", on(3, 17), "Wear green or get pinched.")
        }
        if (region != HolidayRegion.IN) add(HolidayKind.EASTER, "Easter", easter(year), "The bunny is on a tight schedule.")
        if (region in setOf(HolidayRegion.AU, HolidayRegion.NZ)) add(HolidayKind.REMEMBRANCE, "ANZAC Day", on(4, 25), LEST_WE_FORGET)
        if (region in setOf(HolidayRegion.GB, HolidayRegion.CA, HolidayRegion.AU, HolidayRegion.NZ)) {
            add(HolidayKind.REMEMBRANCE, "Remembrance Day", on(11, 11), LEST_WE_FORGET)
        }
        when (region) {
            HolidayRegion.US -> add(HolidayKind.NATIONAL_DAY, "Independence Day", on(7, 4), "Fireworks warm-up in progress.")
            HolidayRegion.CA -> add(HolidayKind.NATIONAL_DAY, "Canada Day", on(7, 1), "Fireworks and a long weekend.")
            HolidayRegion.AU -> add(HolidayKind.NATIONAL_DAY, "Australia Day", on(1, 26), "Barbecue season, officially.")
            HolidayRegion.NZ -> add(HolidayKind.NATIONAL_DAY, "Waitangi Day", on(2, 6), "A day to reflect and recharge.")
            HolidayRegion.ZA -> {
                add(HolidayKind.NATIONAL_DAY, "Freedom Day", on(4, 27), "Celebrating freedom and democracy.")
                add(HolidayKind.NATIONAL_DAY, "Heritage Day", on(9, 24), "Braai Day, officially.")
            }
            HolidayRegion.IN -> {
                add(HolidayKind.NATIONAL_DAY, "Republic Day", on(1, 26), "Flags up, tricolour everywhere.")
                add(HolidayKind.NATIONAL_DAY, "Independence Day", on(8, 15), "Flags up, tricolour everywhere.")
            }
            else -> Unit
        }
        if (region in setOf(HolidayRegion.US, HolidayRegion.CA, HolidayRegion.GB, HolidayRegion.IE, HolidayRegion.OTHER)) {
            add(HolidayKind.HALLOWEEN, "Halloween", on(10, 31), "The pumpkin is judging your costume.")
        }
        if (region == HolidayRegion.GB) add(HolidayKind.BONFIRE_NIGHT, "Bonfire Night", on(11, 5), "Remember, remember.")
        when (region) {
            HolidayRegion.US -> add(HolidayKind.THANKSGIVING, "Thanksgiving", nthWeekday(year, 11, DayOfWeek.THURSDAY, 4), "Stretchy pants: ready.")
            HolidayRegion.CA -> add(HolidayKind.THANKSGIVING, "Thanksgiving", nthWeekday(year, 10, DayOfWeek.MONDAY, 2), "Stretchy pants: ready.")
            else -> Unit
        }
        add(HolidayKind.CHRISTMAS, "Christmas", on(12, 25), "Nice list or naughty list. Decide fast.")
        if (commonwealth) {
            val name = if (region == HolidayRegion.ZA) "Day of Goodwill" else "Boxing Day"
            add(HolidayKind.BOXING_DAY, name, on(12, 26), "Leftovers and the football.")
        }
        add(HolidayKind.NEW_YEARS_EVE, "New Year's Eve", on(12, 31), "Practising the countdown shout.")
    }.sortedBy { it.date }

    /** The next holiday on or after [today] that is within [leadDays], or null in the quiet stretches. */
    fun next(today: LocalDate, region: HolidayRegion, leadDays: Long = LEAD_DAYS): Holiday? =
        (inYear(today.year, region) + inYear(today.year + 1, region))
            .filter { !it.date.isBefore(today) }
            .minByOrNull { it.date }
            ?.takeIf { ChronoUnit.DAYS.between(today, it.date) <= leadDays }

    /** The [n]th [day] of a month: the 4th Thursday of November is US Thanksgiving. */
    fun nthWeekday(year: Int, month: Int, day: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    /** Western Easter Sunday by the Meeus/Jones/Butcher method. */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}
