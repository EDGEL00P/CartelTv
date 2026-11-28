package com.carteltv.tv.data.espn

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Racing series scoreboards (F1 etc.). A Grand Prix weekend is a field event, not a
 * two-sided matchup, so it never goes through [EspnScoreboardClient.Score]: ESPN lists
 * each session (practice, qualifying, race) as one event with many athlete competitors.
 * [Race] keeps only what a schedule row shows: the session name, the circuit, its start,
 * and the status line.
 */
object RacingClient {
    data class Race(
        val name: String,
        /** Series label ("F1", "NASCAR Cup"), so rows from several series can share one section. */
        val series: String,
        /** "Las Vegas Strip Circuit", or null when the feed names it. */
        val circuit: String?,
        val startMs: Long,
        /** "pre" | "in" | "post", same states as the game feeds. */
        val state: String,
        /** The feed's own status ("Final", "Lap 12/50"); blank before the start. */
        val detail: String,
    )

    /** Major field series sharing the racing-style scoreboard schema (events with a competitor field). */
    val SERIES =
        listOf(
            "F1" to "racing/f1",
            "NASCAR Cup" to "racing/nascar-premier",
            "IndyCar" to "racing/irl",
            "PGA Tour" to "golf/pga",
            "LPGA" to "golf/lpga",
            "LIV Golf" to "golf/liv",
            "DP World Tour" to "golf/eur",
            "Champions Tour" to "golf/champions-tour",
        )

    private const val TTL_MS = 10 * 60_000L

    private data class Entry(
        val atMs: Long,
        val races: List<Race>,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun cached(
        label: String,
        path: String,
        maxAgeMs: Long = TTL_MS,
    ): List<Race> {
        val now = System.currentTimeMillis()
        cache[path]?.let { if (now - it.atMs < maxAgeMs) return it.races }
        val races =
            try {
                parse(label, HttpSupport.get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                cache[path]?.races ?: emptyList()
            }
        cache[path] = Entry(now, races)
        return races
    }

    /** One malformed event drops itself, never the rest of the weekend. */
    internal fun parse(
        txt: String,
        series: String = "F1",
    ): List<Race> =
        try {
            val root = HttpSupport.lenientJson.parseToJsonElement(txt).jsonObject
            root["events"]?.jsonArray?.mapNotNull { ev -> runCatching { readEvent(ev, series) }.getOrNull() }.orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    private fun readEvent(
        ev: kotlinx.serialization.json.JsonElement,
        series: String,
    ): Race? {
        val e = ev.jsonObject
        val name = e["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val status = e["status"] as? JsonObject
        val type = status?.get("type") as? JsonObject
        val circuit =
            (e["circuit"] as? JsonObject)?.get("fullName")?.jsonPrimitive?.contentOrNull
                ?: (e["venue"] as? JsonObject)
                    ?.get("fullName")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
        val comp = e["competitions"]?.jsonArray?.firstOrNull() as? JsonObject
        val detail =
            listOfNotNull(
                noteText(comp?.get("notes")),
                type?.get("shortDetail")?.jsonPrimitive?.contentOrNull,
            ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        return Race(
            name = name,
            series = series,
            circuit = circuit,
            startMs = EspnScoreboardMapper.parseStartMs(e["date"]?.jsonPrimitive?.contentOrNull),
            state = type?.get("state")?.jsonPrimitive?.contentOrNull ?: "pre",
            detail = detail,
        )
    }

    /** ESPN `notes` ships as an array of `{type,text}` objects on the site API and a single object on the core API. */
    private fun noteText(notes: kotlinx.serialization.json.JsonElement?): String? =
        when (notes) {
            is JsonArray -> {
                notes
                    .firstOrNull()
                    .let { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: it?.jsonPrimitive?.contentOrNull }
            }

            is JsonObject -> {
                notes.get("text")?.jsonPrimitive?.contentOrNull
            }

            else -> {
                null
            }
        }
}
