package com.carteltv.tv.data.espn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The single entry point for scoreboards. Home, Scores and the goal monitor all read through it, so
 * one request serves them all. It owns the in-memory cache (the source of truth while the app runs), the on-disk
 * snapshot used for a fast cold start, and the rules for how long a board stays fresh.
 */
object ScoreboardRepository {
    private const val DEFAULT_TTL_MS = 45_000L
    private const val QUIET_TTL_MS = 5 * 60_000L
    private const val QUIET_SOON_MS = 20 * 60_000L
    private const val JUST_STARTED_MS = 10 * 60_000L
    private const val MAX_CONCURRENT_FETCHES = 8

    private class Entry(val fetchedAtMs: Long, val games: List<EspnScoreboardClient.Score>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    // 16 leagues over two dates is 32 requests; capping them keeps the TV's connection and CPU from saturating.
    private val fetchGate = Semaphore(MAX_CONCURRENT_FETCHES)

    /** Where scoreboards come from; tests swap in a fake feed. */
    @Volatile internal var source: ScoreboardSource = CompositeScoreboardSource.default

    /** Survives restarts; installed at launch, absent in unit tests. */
    @Volatile internal var snapshots: ScoreboardSnapshotStore? = null

    fun installSnapshots(dir: java.io.File) { snapshots = ScoreboardSnapshotStore(dir) }

    internal fun clearCache() = cache.clear()

    /** No game in progress, none starting within 20 minutes (or just past its start), none with an unknown start. */
    internal fun isQuiet(games: List<EspnScoreboardClient.Score>, nowMs: Long): Boolean = games.none { g ->
        g.state == "in" || (g.state == "pre" && (g.startMs <= 0L || g.startMs - nowMs in -JUST_STARTED_MS..QUIET_SOON_MS))
    }

    /**
     * A scoreboard from the shared cache, fetched again once it is older than [maxAgeMs] (or when [force]).
     * Callers that poll pass a short [maxAgeMs], so two of them asking within moments share one request.
     * A fetch that fails returns the last good board instead of an empty one: a dropped connection must not
     * wipe the scores on screen.
     */
    suspend fun scoreboard(
        sportPath: String, force: Boolean = false, date: String? = null, maxAgeMs: Long = DEFAULT_TTL_MS,
    ): List<EspnScoreboardClient.Score> {
        val key = keyOf(sportPath, date)
        if (!force) freshEntry(key, maxAgeMs)?.let { return it }
        // Concurrent callers for one board queue here, then find it fresh: one request serves them all.
        return fetchLocks.getOrPut(key) { Mutex() }.withLock {
            if (!force) freshEntry(key, maxAgeMs)?.let { return@withLock it }
            val fetched = withContext(Dispatchers.IO) {
                val result = fetchGate.withPermit { source.scoreboardOrNull(sportPath, date) }
                if (result != null) snapshots?.write(key, result)
                result
            } ?: return@withLock cache[key]?.games ?: emptyList()
            cache[key] = Entry(System.currentTimeMillis(), fetched)
            snapshots?.write(key, fetched)
            fetched
        }
    }

    /**
     * The last board known for a league and day, from memory or from disk, with no network. It lets a cold start
     * paint real games at once while the fresh pull runs; an old snapshot is dropped, never shown as today's.
     */
    fun lastKnown(sportPath: String, date: String? = null): List<EspnScoreboardClient.Score>? {
        val key = keyOf(sportPath, date)
        return cache[key]?.games ?: snapshots?.read(key)
    }

    private fun freshEntry(key: String, maxAgeMs: Long): List<EspnScoreboardClient.Score>? {
        val entry = cache[key] ?: return null
        val now = System.currentTimeMillis()
        // A board with nothing live and nothing about to start cannot change soon, so it is kept longer.
        val ttl = if (isQuiet(entry.games, now)) maxOf(maxAgeMs, QUIET_TTL_MS) else maxAgeMs
        return entry.games.takeIf { now - entry.fetchedAtMs < ttl }
    }

    private fun keyOf(sportPath: String, date: String?) = "$sportPath|${date.orEmpty()}"
}
