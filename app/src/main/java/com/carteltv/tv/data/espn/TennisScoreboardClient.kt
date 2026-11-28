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
 * Tennis scoreboards (ATP/WTA). Tennis is the one sport whose scoreboard nests matches
 * under `events[].groupings[].competitions[]` instead of `events[].competitions[]`, so it
 * cannot go through the shared event parser. Each competition is one match: two athlete
 * competitors, per-competitor score strings, and a status line ("QF", "7-5 6-3").
 */
object TennisScoreboardClient {
    data class Match(
        /** "J. Sinner vs C. Alcaraz" built from the two sides. */
        val name: String,
        val tour: String,
        val tournament: String,
        val startMs: Long,
        val state: String,
        /** The feed's own status: round note and/or live score line. */
        val detail: String,
    )

    val SERIES = listOf("ATP" to "tennis/atp", "WTA" to "tennis/wta")

    private const val TTL_MS = 10 * 60_000L

    private data class Entry(
        val atMs: Long,
        val matches: List<Match>,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun cached(
        label: String,
        path: String,
        maxAgeMs: Long = TTL_MS,
    ): List<Match> {
        val now = System.currentTimeMillis()
        cache[path]?.let { if (now - it.atMs < maxAgeMs) return it.matches }
        val matches =
            try {
                parse(label, HttpSupport.get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                cache[path]?.matches ?: emptyList()
            }
        cache[path] = Entry(now, matches)
        return matches
    }

    internal fun parse(
        txt: String,
        tour: String,
    ): List<Match> =
        try {
            val root = HttpSupport.lenientJson.parseToJsonElement(txt).jsonObject
            val out = ArrayList<Match>()
            root["events"]?.jsonArray?.forEach { ev ->
                runCatching {
                    val e = ev.jsonObject
                    val tournament = e["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    // Groupings = tournament rounds; competitions under each = the matches of that round.
                    e["groupings"]?.jsonArray?.forEach { gr ->
                        (gr.jsonObject["competitions"]?.jsonArray)?.forEach { c ->
                            runCatching { readMatch(c.jsonObject, tour, tournament) }.getOrNull()?.let(out::add)
                        }
                    }
                    // Some tours list matches straight under the event; keep those too.
                    e["competitions"]?.jsonArray?.forEach { c ->
                        runCatching { readMatch(c.jsonObject, tour, tournament) }.getOrNull()?.let(out::add)
                    }
                }
            }
            out.distinctBy { it.name to it.startMs }.sortedBy { it.startMs }
        } catch (_: Exception) {
            emptyList()
        }

    private fun readMatch(
        c: JsonObject,
        tour: String,
        tournament: String,
    ): Match? {
        val comps = c["competitors"]?.jsonArray ?: return null
        if (comps.size < 2) return null

        fun name(i: Int): String? {
            val o = comps[i] as? JsonObject ?: return null
            val athlete = o["athlete"] as? JsonObject
            return athlete?.get("shortName")?.jsonPrimitive?.contentOrNull
                ?: athlete?.get("displayName")?.jsonPrimitive?.contentOrNull
                ?: (o["team"] as? JsonObject)?.get("displayName")?.jsonPrimitive?.contentOrNull
        }
        val a = name(0) ?: return null
        val b = name(1) ?: return null
        val status = c["status"] as? JsonObject
        val type = status?.get("type") as? JsonObject
        val detail =
            listOfNotNull(
                (c["notes"] as? JsonArray)
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull,
                type?.get("detail")?.jsonPrimitive?.contentOrNull,
            ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        val start = c["date"]?.jsonPrimitive?.contentOrNull?.let { EspnScoreboardMapper.parseStartMs(it) } ?: 0L
        return Match(
            name = "$a vs $b",
            tour = tour,
            tournament = tournament,
            startMs = start,
            state = type?.get("state")?.jsonPrimitive?.contentOrNull ?: "pre",
            detail = listOf(tour, detail).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }
}
