package com.carteltv.tv.data.finance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class ExchangeRatesTest {
    private val savedLocale = Locale.getDefault()

    @Before
    fun useOneLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    // Frankfurter's answer for a week of ringgit rates, read on 25 Nov 2026.
    private val feed = """
        {"amount":1.0,"base":"MYR","start_date":"2026-11-18","end_date":"2026-11-24","rates":{
          "2026-11-18":{"EUR":0.22151,"USD":0.24296,"JPY":38.3},
          "2026-11-19":{"EUR":0.22121,"USD":0.24316,"JPY":38.1},
          "2026-11-20":{"EUR":0.22126,"USD":0.24316,"JPY":38.4},
          "2026-11-23":{"EUR":0.22172,"USD":0.24254,"JPY":37.9},
          "2026-11-24":{"EUR":0.22164,"USD":0.24299,"JPY":38.2}}}
    """.trimIndent()

    @Test
    fun theNewestDayIsTheRateAndTheDayBeforeIsWhatItMovedFrom() {
        val rates = ExchangeRateService.parse(feed, "MYR", listOf("USD", "EUR", "JPY"))!!
        assertEquals("MYR", rates.base)
        assertEquals("2026-11-24", rates.date)
        assertEquals(listOf("USD", "EUR", "JPY"), rates.rates.map { it.code })
        assertEquals(0.24299, rates.rates[0].value, 0.0)
        assertEquals(0.24254, rates.rates[0].previous!!, 0.0)
        assertEquals(0.1852, rates.rates[0].changePct!!, 0.001)
        assertEquals(-0.0361, rates.rates[1].changePct!!, 0.001)
        assertEquals(0.7916, rates.rates[2].changePct!!, 0.001)
    }

    @Test
    fun aCurrencyNotQuotedThatDayIsLeftOutAndOneDayAloneHasNoMove() {
        val single = """{"base":"MYR","rates":{"2026-11-24":{"USD":0.24299}}}"""
        val rates = ExchangeRateService.parse(single, "MYR", listOf("USD", "EUR"))!!
        assertEquals(listOf("USD"), rates.rates.map { it.code })
        assertNull(rates.rates[0].previous)
        assertNull(rates.rates[0].changePct)
    }

    @Test
    fun noRatesIsNoAnswer() {
        assertNull(ExchangeRateService.parse("""{"base":"MYR","rates":{}}""", "MYR", listOf("USD")))
        assertNull(ExchangeRateService.parse("not json", "MYR", listOf("USD")))
    }

    @Test
    fun theLocalCurrencyIsTheOnesTheSourceQuotes() {
        assertEquals("MYR", ExchangeRateService.currencyFor("my"))
        assertEquals("USD", ExchangeRateService.currencyFor("US"))
        assertEquals("IDR", ExchangeRateService.currencyFor("ID"))
        assertEquals("EUR", ExchangeRateService.currencyFor("FR"))
        // The source quotes no riyal, rand or rupee, so a country using one names no base.
        assertNull(ExchangeRateService.currencyFor("SA"))
        assertNull(ExchangeRateService.currencyFor("PK"))
        assertNull(ExchangeRateService.currencyFor("ZZ"))
        assertNull(ExchangeRateService.currencyFor(""))
        assertNull(ExchangeRateService.currencyFor(null))
        assertNull(ExchangeRateService.currencyFor("Malaysia"))
    }

    @Test
    fun aRateShowsWholeUnitsWhenLargeAndRoomWhenSmall() {
        assertEquals("18,229", ExchangeRateService.format(18229.0))
        assertEquals("4.116", ExchangeRateService.format(4.1155))
        assertEquals("12.35", ExchangeRateService.format(12.3456))
        assertEquals("0.9122", ExchangeRateService.format(0.91216))
        assertEquals("0.2123", ExchangeRateService.format(0.21231))
    }

    @Test
    fun aMoveCarriesItsSignEitherWay() {
        assertEquals("+0.32%", ExchangeRateService.signedPercent(0.32))
        assertEquals("+0.00%", ExchangeRateService.signedPercent(0.0))
        assertEquals("−0.14%", ExchangeRateService.signedPercent(-0.14))
    }

    @Test
    fun theQuotedListHoldsTheWatchlistAndNoDuplicate() {
        assertEquals(ExchangeRateService.currencies.size, ExchangeRateService.currencies.distinct().size)
        assertTrue(ExchangeRateService.defaultWatch.all { it in ExchangeRateService.currencies })
        assertTrue(ExchangeRateService.DEFAULT_BASE in ExchangeRateService.currencies)
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
