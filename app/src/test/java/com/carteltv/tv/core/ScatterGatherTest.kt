package com.carteltv.tv.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Outcome-level tests for the deadline-bounded scatter-gather: which results
 * survive and which jobs get dropped. Assertions never measure wall time; the
 * windows are short so a run is quick, and an implementation that stopped
 * returning would fail the 2 s outer guards instead of hanging the suite.
 */
class ScatterGatherTest {

    private val limits = ScatterGatherLimits(
        concurrency = 4,
        jobTimeout = 300.milliseconds,
        earlyAfter = 100.milliseconds,
        minResults = 2,
        hardDeadline = 500.milliseconds,
    )

    @Test fun `a quorum in the early window returns without the slow jobs`() = runBlocking {
        val result = scatterGather(
            jobs = listOf(
                suspend { listOf("fast-1", "fast-2") } to false,
                suspend { awaitCancellation() } to false,
            ),
            limits = limits,
        )
        assertEquals(listOf("fast-1", "fast-2"), result)
    }

    @Test fun `below the quorum the call returns at the hard deadline`() = runBlocking {
        val result = withTimeout(2.seconds) {
            scatterGather(
                jobs = listOf(
                    suspend { listOf("only") } to false,
                    suspend { awaitCancellation() } to false,
                ),
                limits = limits,
            )
        }
        assertEquals(listOf("only"), result)
    }

    @Test fun `a job that fails contributes nothing and does not fail the search`() = runBlocking {
        val result = scatterGather(
            jobs = listOf(
                suspend { throw IllegalStateException("dead source") } to false,
                suspend { listOf("good-1", "good-2") } to false,
            ),
            limits = limits,
        )
        assertEquals(listOf("good-1", "good-2"), result)
    }

    @Test fun `a job that outlasts its own timeout contributes nothing`() = runBlocking {
        val result = withTimeout(2.seconds) {
            scatterGather(
                jobs = listOf(
                    suspend { awaitCancellation() } to false,
                    suspend { delay(50.milliseconds); listOf("prompt") } to false,
                ),
                limits = limits,
            )
        }
        assertEquals(listOf("prompt"), result)
    }

    @Test fun `everything answered returns without waiting for the early window`() = runBlocking {
        val result = scatterGather(
            jobs = listOf(
                suspend { listOf("a") } to false,
                suspend { listOf("b") } to false,
            ),
            limits = limits,
        )
        assertEquals(listOf("a", "b"), result)
    }

    @Test fun `the quorum only counts results the caller accepts`() = runBlocking {
        val result = withTimeout(2.seconds) {
            scatterGather(
                jobs = listOf(
                    suspend { listOf("junk-1", "junk-2") } to false,
                    suspend { awaitCancellation() } to false,
                ),
                countsTowardQuorum = { false },
                limits = limits,
            )
        }
        assertEquals(listOf("junk-1", "junk-2"), result)
    }

    @Test fun `an early exit waits for the gated passes so their siblings land`() = runBlocking {
        val result = withTimeout(2.seconds) {
            scatterGather(
                jobs = listOf(
                    suspend { listOf("base-1", "base-2") } to false,
                    suspend { awaitCancellation() } to true,
                    suspend { delay(200.milliseconds); listOf("late-3") } to false,
                ),
                limits = limits,
            )
        }
        // The gated pass is still running at the 100 ms window, so the call must not exit there: the
        // gated job's own 300 ms timeout lets its sibling's 200 ms result land before the exit.
        assertEquals(setOf("base-1", "base-2", "late-3"), result.toSet())
    }

    @Test fun `cancelling the caller cancels the jobs`() = runBlocking {
        val never: suspend () -> List<String> = { awaitCancellation() }
        val work = async {
            scatterGather(
                jobs = listOf(never to false),
                limits = limits,
            )
        }
        work.cancel()
        work.join()
        assertTrue(work.isCancelled)
    }
}
