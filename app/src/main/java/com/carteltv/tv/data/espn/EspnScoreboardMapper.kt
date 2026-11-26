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
 * Maps ESPN's scoreboard JSON to [EspnScoreboardClient.Score]. The feed is undocumented and varies by sport, so every
 * field is read defensively; one malformed event drops itself, never the whole league.
 */
internal object EspnScoreboardMapper {

    /** The whole `scoreboard` document. Throws if the document is not JSON, so the caller can tell failure from "no games". */
    fun toScores(json: String, maxEvents: Int): List<EspnScoreboardClient.Score> {
        val root = HttpSupport.lenientJson.parseToJsonElement(json).jsonObject
        val events = root["events"]?.jsonArray ?: JsonArray(emptyList())
        val leagueLogo = ((root["leagues"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { (it["logos"] as? JsonArray)?.firstOrNull() as? JsonObject }
            ?.get("href")?.jsonPrimitive?.contentOrNull
        return events.take(maxEvents).mapNotNull { ev -> runCatching { toScore(ev, leagueLogo) }.getOrNull() }
    }

    /** ESPN dates are ISO-8601 UTC ("2026-11-22T03:00Z"); epoch ms, 0 when unparseable. */
    fun parseStartMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        // ESPN drops the seconds ("...T03:00Z"), which Instant.parse rejects.
        val full = if (raw.length == MINUTE_PRECISION_LENGTH && raw.endsWith("Z")) raw.dropLast(1) + ":00Z" else raw
        return runCatching { java.time.Instant.parse(full).toEpochMilli() }.getOrDefault(0L)
    }

    private const val MINUTE_PRECISION_LENGTH = 17

    private class Side(
        val abbr: String?, val logo: String?, val score: String?, val name: String?, val record: String?,
        val color: String?, val altColor: String?, val id: String?,
    )

    private fun String?.nonBlank() = this?.takeIf { it.isNotBlank() }

    private fun JsonObject?.text(key: String): String? = this?.get(key)?.jsonPrimitive?.contentOrNull

    private fun toScore(element: JsonElement, leagueLogo: String?): EspnScoreboardClient.Score? {
        val event = element.jsonObject
        val name = event.text("name") ?: return null
        val status = event["status"] as? JsonObject
        val type = status?.get("type") as? JsonObject
        val detail = status.text("displayClock") ?: type.text("detail") ?: ""
        val state = type.text("state") ?: ""
        val competition = event["competitions"]?.jsonArray?.firstOrNull() as? JsonObject ?: return null
        val competitors = competition["competitors"]?.jsonArray ?: return null
        if (competitors.size < 2) return null
        val home = side(competitors[0] as JsonObject)
        val away = side(competitors[1] as JsonObject)

        val broadcasts = competition["broadcasts"] as? JsonArray
        val geoBroadcasts = competition["geoBroadcasts"] as? JsonArray
        val network = ((broadcasts?.firstOrNull() as? JsonObject)?.get("names") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: ((geoBroadcasts?.firstOrNull() as? JsonObject)?.get("media") as? JsonObject).text("shortName")
        val networks = buildList {
            broadcasts?.forEach { bc -> ((bc as? JsonObject)?.get("names") as? JsonArray)?.forEach { n -> n.jsonPrimitive.contentOrNull?.let(::add) } }
            geoBroadcasts?.forEach { bc -> (((bc as? JsonObject)?.get("media")) as? JsonObject).text("shortName")?.let(::add) }
        }.filter { it.isNotBlank() }.distinct()

        val gameImage = gameImage(competition)
        val venue = competition["venue"] as? JsonObject
        val address = venue?.get("address") as? JsonObject
        val location = listOfNotNull(address.text("city"), address.text("state") ?: address.text("country"))
            .filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { null }

        return EspnScoreboardClient.Score(
            name, detail, state, home.logo, home.abbr, home.score, away.logo, away.abbr, away.score,
            nameA = home.name, nameB = away.name, recordA = home.record, recordB = away.record,
            startMs = parseStartMs(event.text("date")),
            network = network, leagueLogo = leagueLogo, networks = networks,
            venue = venue.text("fullName").nonBlank(),
            location = location,
            indoor = venue.text("indoor")?.toBooleanStrictOrNull(),
            period = status?.get("period")?.jsonPrimitive?.intOrNull ?: 0,
            clock = status.text("displayClock").nonBlank(),
            shortDetail = type.text("shortDetail").nonBlank(),
            colorA = home.color, altColorA = home.altColor, colorB = away.color, altColorB = away.altColor,
            odds = oddsLine(competition),
            idA = home.id, idB = away.id,
            image = gameImage,
        )
    }

    /** A real highlight thumbnail from the event, before the slower league-news photo lookup. */
    private fun gameImage(competition: JsonObject): String? {
        val highlights = competition["highlights"] as? JsonArray
        highlights?.forEach { el ->
            val video = el as? JsonObject ?: return@forEach
            video.text("thumbnail")?.let { return it }
        }
        val headlines = competition["headlines"] as? JsonArray
        headlines?.forEach { el ->
            val headline = el as? JsonObject ?: return@forEach
            val videos = headline["video"] as? JsonArray ?: return@forEach
            videos.forEach { v ->
                (v as? JsonObject)?.text("thumbnail")?.let { return it }
            }
        }
        return null
    }

    private fun side(competitor: JsonObject): Side {
        val team = competitor["team"] as? JsonObject
        val athlete = competitor["athlete"] as? JsonObject
        val abbr = team.text("abbreviation") ?: athlete.text("shortName") ?: athlete.text("displayName")
        val logo = team.text("logo")
            ?: (team?.get("logos") as? JsonArray)?.firstOrNull()?.jsonObject.text("href")
            ?: (athlete?.get("headshot") as? JsonObject).text("href")
            ?: (athlete?.get("flag") as? JsonObject).text("href")
        val name = team.text("shortDisplayName") ?: team.text("displayName") ?: athlete.text("displayName")
        val record = ((competitor["records"] as? JsonArray)?.firstOrNull() as? JsonObject).text("summary")
        return Side(
            abbr, logo, competitor.text("score"), name, record,
            color = team.text("color").nonBlank(), altColor = team.text("alternateColor").nonBlank(),
            id = team.text("id"),
        )
    }

    /** The first odds entry is the priority book; "details" is the broadcast-style line ("CIN -6"). */
    private fun oddsLine(competition: JsonObject): String? {
        val odds = (competition["odds"] as? JsonArray)?.firstOrNull() as? JsonObject
        return buildList {
            odds.text("details").nonBlank()?.let(::add)
            odds.text("overUnder").nonBlank()?.let { add("O/U $it") }
        }.joinToString(" \u00b7 ").ifBlank { null }
    }
}
