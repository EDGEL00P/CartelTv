package com.carteltv.tv.data.espn

import com.carteltv.tv.data.network.HttpSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * A real photograph for a game: ESPN's editorial photo of one of its teams, taken from the league's news feed. The
 * feed tags each article with the teams it is about, so a game finds its picture by team id. Soccer and other
 * feeds that tag no team simply have none, and callers fall back to the team marks.
 */
object GamePhotos {
    private class Entry(val atMs: Long, val byTeam: Map<String, String>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 20 * 60_000L
    private const val ARTICLES = 50
    private const val BASE_URL = "https://site.api.espn.com/apis/site/v2/sports"

    /** The photo already fetched for [game], with no network, or null. */
    fun cached(game: EspnScoreboardClient.Score): String? =
        game.image ?: pick(game, cache[game.leaguePath]?.byTeam.orEmpty())

    /** The photo for [game]: the home side's first, then the visitors'. */
    suspend fun forGame(game: EspnScoreboardClient.Score): String? {
        game.image?.let { return it }
        val path = game.leaguePath ?: return null
        return pick(game, teams(path))
    }

    private fun pick(game: EspnScoreboardClient.Score, byTeam: Map<String, String>): String? =
        listOfNotNull(game.idA, game.idB).firstNotNullOfOrNull { byTeam[it] }

    private suspend fun teams(path: String): Map<String, String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[path]?.takeIf { now - it.atMs < TTL_MS }?.let { return@withContext it.byTeam }
        val fresh = try {
            parse(HttpSupport.get("$BASE_URL/$path/news?limit=$ARTICLES"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed fetch keeps the last good map: a dropped connection must not strip the pictures already on screen.
            return@withContext cache[path]?.byTeam.orEmpty()
        }
        cache[path] = Entry(now, fresh)
        fresh
    }

    /** Team id to the first (newest) wide photo of an article about that team. */
    internal fun parse(json: String): Map<String, String> {
        val articles = HttpSupport.lenientJson.parseToJsonElement(json).jsonObject["articles"] as? JsonArray ?: return emptyMap()
        val byTeam = LinkedHashMap<String, String>()
        for (article in articles) {
            val a = article as? JsonObject ?: continue
            val photo = (a["images"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter { (it["width"]?.jsonPrimitive?.intOrNull ?: 0) >= MIN_WIDTH }
                ?.maxByOrNull { it["width"]?.jsonPrimitive?.intOrNull ?: 0 }
                ?.get("url")?.jsonPrimitive?.contentOrNull
                ?: continue
            (a["categories"] as? JsonArray)?.forEach { category ->
                val c = category as? JsonObject ?: return@forEach
                if (c["type"]?.jsonPrimitive?.contentOrNull == "team") {
                    c["teamId"]?.jsonPrimitive?.contentOrNull?.let { byTeam.putIfAbsent(it, photo) }
                }
            }
        }
        return byTeam
    }

    private const val MIN_WIDTH = 500
}