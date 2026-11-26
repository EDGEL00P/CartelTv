package com.carteltv.tv.ui.hero
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60 * SECOND_MS
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** The pre-kickoff window in which a hero slide escalates: "STARTING SOON" in the live colour. */
internal const val WARMUP_MS = 15 * MINUTE_MS

/**
 * How a broadcast graphic counts down: "2d 4h" while it is days away, then a ticking "03:07:42",
 * and "00:00:00" once the start has passed. Never negative.
 */
fun countdownText(remainingMs: Long): String {
    val ms = remainingMs.coerceAtLeast(0L)
    if (ms >= DAY_MS) return "${ms / DAY_MS}d ${ms % DAY_MS / HOUR_MS}h"
    return "%02d:%02d:%02d".format(ms / HOUR_MS, ms % HOUR_MS / MINUTE_MS, ms % MINUTE_MS / SECOND_MS)
}

/** The wall clock, re-read on each whole second so countdowns tick in step with it. */
@Composable
internal fun rememberTickingNowMs(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Never zero: a 0 delay skips the tick entirely and the clock drifts out of step.
            delay(((SECOND_MS - System.currentTimeMillis() % SECOND_MS) % SECOND_MS).coerceAtLeast(1L))
            now = System.currentTimeMillis()
        }
    }
    return now
}
