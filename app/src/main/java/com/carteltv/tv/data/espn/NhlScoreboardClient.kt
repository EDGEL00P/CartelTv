package com.carteltv.tv.data.espn

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The NHL's own scoreboard (api-web.nhle.com), a second opinion next to ESPN for `hockey/nhl`. It lists every
 * national and regional broadcaster per game, in both countries, where ESPN names one. Endpoint and the
 * pre-game fields (teams, start time, broadcasts) were checked against the live API; the in-game clock fields
 * are read defensively because no live game was available to check them.
 */
class NhlScoreboardClient : ScoreboardSource {

    override suspend fun scoreboard(sportPath: String, date: String?): List<EspnScoreboardClient.Score> =
        scoreboardOrNull(sportPath, date) ?: emptyList()

    /** An empty list for any other league (nothing to ask), null when the NHL feed could not be read. */
    override suspend fun scoreboardOrNull(sportPath: String, date: String?): List<EspnScoreboardClient.Score>? {
        if (sportPath != PATH) return emptyList()
        return try {
            val day = date?.takeIf { it.length == 8 }?.let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}" } ?: "now"
            parse(HttpSupport.get("https://api-web.nhle.com/v1/score/$day"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PATH = "hockey/nhl"

        /** Pure parse of the `score/{date}` document, for tests. */
        fun parse(json: String): List<EspnScoreboardClient.Score> {
            val games = HttpSupport.lenientJson.parseToJsonElement(json).jsonObject["games"]?.jsonArray ?: return emptyList()
            return games.take(40).mapNotNull { g -> runCatching { parseGame(g) }.getOrNull() }
        }

        private fun str(o: JsonObject?, key: String): String? = o?.get(key)?.jsonPrimitive?.contentOrNull

        private fun teamName(t: JsonObject?): String? = (t?.get("name") as? JsonObject)?.let { str(it, "default") }

        private fun state(raw: String?): String = when (raw?.uppercase()) {
            "LIVE", "CRIT" -> "in"
            "OFF", "FINAL" -> "post"
            else -> "pre"
        }

        private fun startMs(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
        }

        private fun detail(g: JsonObject, state: String): String = when (state) {
            "in" -> {
                val period = ((g["periodDescriptor"] as? JsonObject)?.get("number")?.jsonPrimitive?.intOrNull)
                    ?: g["period"]?.jsonPrimitive?.intOrNull
                val left = str(g["clock"] as? JsonObject, "timeRemaining")
                listOfNotNull(period?.let { "P$it" }, left).joinToString(" ")
            }
            "post" -> "Final"
            else -> ""
        }

        /** National broadcasters first, then regional; each name once. */
        private fun networks(g: JsonObject): List<String> =
            ((g["tvBroadcasts"] as? JsonArray) ?: JsonArray(emptyList()))
                .mapNotNull { it as? JsonObject }
                .sortedBy { if (str(it, "market") == "N") 0 else 1 }
                .mapNotNull { str(it, "network") }
                .filter { it.isNotBlank() }.distinct()

        private fun parseGame(el: JsonElement): EspnScoreboardClient.Score? {
            val g = el.jsonObject
            val home = g["homeTeam"] as? JsonObject ?: return null
            val away = g["awayTeam"] as? JsonObject ?: return null
            val homeName = teamName(home) ?: return null
            val awayName = teamName(away) ?: return null
            val st = state(str(g, "gameState"))
            val nets = networks(g)
            return EspnScoreboardClient.Score(
                name = "$awayName at $homeName", detail = detail(g, st), state = st,
                logoA = str(home, "logo"), abbrA = str(home, "abbrev"), scoreA = home["score"]?.jsonPrimitive?.contentOrNull,
                logoB = str(away, "logo"), abbrB = str(away, "abbrev"), scoreB = away["score"]?.jsonPrimitive?.contentOrNull,
                nameA = homeName, nameB = awayName, recordA = str(home, "record"), recordB = str(away, "record"),
                startMs = startMs(str(g, "startTimeUTC")), network = nets.firstOrNull(), networks = nets,
                venue = (g["venue"] as? JsonObject)?.let { str(it, "default") }?.takeIf { it.isNotBlank() },
                location = (g["venueLocation"] as? JsonObject)?.let { str(it, "default") }?.takeIf { it.isNotBlank() },
                period = ((g["periodDescriptor"] as? JsonObject)?.get("number")?.jsonPrimitive?.intOrNull)
                    ?: g["period"]?.jsonPrimitive?.intOrNull ?: 0,
                clock = str(g["clock"] as? JsonObject, "timeRemaining")?.takeIf { it.isNotBlank() },
                shortDetail = detail(g, st).ifBlank { null },
            )
        }
    }
}
