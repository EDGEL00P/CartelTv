package com.carteltv.tv.core

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Budget for [scatterGather]; the defaults follow Magnetio's scraper (github.com/peterdsp/Magnetio). */
data class ScatterGatherLimits(
    val concurrency: Int = 12,
    val jobTimeout: Duration = 15.seconds,
    /** First moment an early exit may fire; a quorum before this still waits for it. */
    val earlyAfter: Duration = 6.seconds,
    /** Job results counted by [countsTowardQuorum] needed for an early exit. */
    val minResults: Int = 10,
    val hardDeadline: Duration = jobTimeout + 2.seconds,
) {
    init {
        require(concurrency >= 1) { "concurrency must be at least 1" }
    }
}

/**
 * Fan a search out to every job and return as soon as stopping is safe.
 *
 * Two ways out, whichever lands first:
 *  - an early exit, allowed once [ScatterGatherLimits.earlyAfter] has elapsed,
 *    when at least [ScatterGatherLimits.minResults] results have arrived and
 *    counted via [countsTowardQuorum], and no job flagged as gated (second
 *    element of the pair, e.g. a localized-title pass) is still running;
 *  - the hard deadline, after which whatever has arrived is returned.
 *
 * Everything else lands on the same terms: a job that fails or outlives
 * [ScatterGatherLimits.jobTimeout] contributes nothing; results that arrive
 * after the exit are dropped; jobs still queued are cancelled, as are all jobs
 * on return, so the call never outlasts the hard deadline and cancelling the
 * caller cancels every job.
 */
suspend fun <T> scatterGather(
    jobs: List<Pair<suspend () -> List<T>, Boolean>>,
    countsTowardQuorum: (T) -> Boolean = { true },
    limits: ScatterGatherLimits = ScatterGatherLimits(),
): List<T> = coroutineScope {
    if (jobs.isEmpty()) return@coroutineScope emptyList()

    val collected = Collections.synchronizedList(mutableListOf<T>())
    val matched = AtomicInteger()
    val completed = AtomicInteger()
    val pendingGated = AtomicInteger(jobs.count { it.second })
    val gate = CompletableDeferred<Unit>()
    val permits = Semaphore(limits.concurrency)
    val start = TimeSource.Monotonic.markNow()

    fun tryFinish() {
        val everythingAnswered = completed.get() == jobs.size
        val windowOver = start.elapsedNow() >= limits.earlyAfter
        val quorum = pendingGated.get() == 0 && matched.get() >= limits.minResults
        if (everythingAnswered || (windowOver && quorum)) gate.complete(Unit)
    }

    val earlyTimer = launch {
        delay(limits.earlyAfter)
        tryFinish()
    }

    val workers = jobs.map { (job, gated) ->
        launch {
            permits.withPermit {
                try {
                    val results = withTimeoutOrNull(limits.jobTimeout) { job() }.orEmpty()
                    collected += results
                    matched.addAndGet(results.count(countsTowardQuorum))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A dead source contributes nothing and must not fail the search.
                } finally {
                    if (gated) pendingGated.decrementAndGet()
                    completed.incrementAndGet()
                    tryFinish()
                }
            }
        }
    }

    withTimeoutOrNull(limits.hardDeadline) { gate.await() }
    (listOf(earlyTimer) + workers).forEach { it.cancel() }
    collected.toList()
}
