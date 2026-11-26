package com.carteltv.tv.data.finance

import com.carteltv.tv.data.network.HttpSupport
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/* ═══════════════════════════════════════════════════════════
   EXCHANGE RATES
   ─────────────────────────────────────────────────────────────
   The European Central Bank's reference rates, the ones banks and
   newspapers quote, through the public Frankfurter service: no
   key, no account. They are fixed once a working day at about 16:00
   Frankfurt time, so the card says which day's rates it shows and
   how each moved from the working day before.
   ═══════════════════════════════════════════════════════════ */

/** One currency against the base: what a unit of the base buys, and what it bought the working day before. */
data class Rate(val code: String, val value: Double, val previous: Double?) {
    /** The move since the working day before, in percent; null while there is no earlier day to compare with. */
    val changePct: Double? get() = previous?.takeIf { it > 0 }?.let { (value / it - 1.0) * 100.0 }
}

/** The latest day's rates for one base currency. */
data class ExchangeRates(val base: String, val date: String, val rates: List<Rate>)

object ExchangeRateService {
    /** Every currency the source quotes: the central bank's own list. */
    val currencies: List<String> = listOf(
        "AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR", "ISK", "JPY",
        "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "USD", "ZAR",
    )

    /** The currencies a card shows when the household has not chosen any. */
    val defaultWatch: List<String> = listOf("USD", "EUR", "GBP", "JPY", "SGD")

    /** The base when nothing points at a local one. */
    const val DEFAULT_BASE = "USD"

    private const val HISTORY_DAYS = 7L
    private const val TTL_MS = 6 * 3_600_000L

    private data class Entry(val atMs: Long, val value: ExchangeRates)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** The latest rates of [symbols] against [base], re-read at most every six hours; null until the service answers. */
    suspend fun latest(base: String, symbols: List<String>): ExchangeRates? {
        val quoted = symbols.filter { it in currencies && it != base }.distinct()
        if (base !in currencies || quoted.isEmpty()) return null
        val key = "$base:${quoted.joinToString(",")}"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.atMs < TTL_MS }?.let { return it.value }
        val fresh = withContext(Dispatchers.IO) {
            suspendRunCatching {
                val today = LocalDate.now(ZoneId.systemDefault())
                val url = "https://api.frankfurter.dev/v1/${today.minusDays(HISTORY_DAYS)}..$today" +
                    "?base=$base&symbols=${quoted.joinToString(",")}"
                parse(HttpSupport.get(url, connectMs = 8_000, readMs = 12_000), base, quoted)
            }.getOrNull()
        }
        if (fresh != null) cache[key] = Entry(now, fresh)
        return fresh
    }

    /** The last two days in [body]: the newest as the day's rates, the one before as what they moved from. */
    internal fun parse(body: String, base: String, symbols: List<String>): ExchangeRates? = runCatching {
        val days = (HttpSupport.json.parseToJsonElement(body).jsonObject["rates"] as? JsonObject)
            ?.mapNotNull { (date, rates) -> (rates as? JsonObject)?.let { date to it } }
            ?.sortedBy { it.first }
            ?: return null
        val latest = days.lastOrNull() ?: return null
        val before = days.getOrNull(days.size - 2)?.second
        fun JsonObject.quote(code: String): Double? = this[code]?.jsonPrimitive?.content?.toDoubleOrNull()
        val rates = symbols.mapNotNull { code ->
            latest.second.quote(code)?.let { Rate(code, it, before?.quote(code)) }
        }
        if (rates.isEmpty()) return null
        ExchangeRates(base, latest.first, rates)
    }.getOrNull()

    /** The currency a country uses, if the source quotes it; null for a country it does not. */
    fun currencyFor(countryCode: String?): String? {
        val code = countryCode?.trim()?.uppercase().orEmpty()
        if (code.length != 2) return null
        return runCatching { Currency.getInstance(Locale.Builder().setRegion(code).build()).currencyCode }
            .getOrNull()
            ?.takeIf { it in currencies }
    }

    /** A rate as a card shows it: whole units for the big numbers, more room as they get smaller. */
    fun format(value: Double): String {
        val decimals = when {
            value >= 100 -> 0
            value >= 10 -> 2
            value >= 1 -> 3
            else -> 4
        }
        return "%,.${decimals}f".format(Locale.getDefault(), value)
    }

    /** A move in percent with its sign: "+0.32%" or "−0.14%". */
    fun signedPercent(pct: Double): String {
        val sign = if (pct >= 0) "+" else "−"
        return sign + "%.2f%%".format(Locale.getDefault(), kotlin.math.abs(pct))
    }
}
