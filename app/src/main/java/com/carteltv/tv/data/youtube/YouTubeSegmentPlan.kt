package com.carteltv.tv.data.youtube

/**
 * Pure SponsorBlock scheduling: normalises raw segments and decides, from the player position,
 * whether to seek, mute or wait. No player or coroutine dependencies, so it is unit-testable.
 */
class YouTubeSegmentPlan(raw: List<YouTubeApiClient.Segment>) {

    sealed interface Decision {
        data object None : Decision
        data class Seek(val toSec: Double, val segment: YouTubeApiClient.Segment) : Decision
        data class Mute(val segment: YouTubeApiClient.Segment?, val entered: Boolean) : Decision
    }

    /** "full" entries label the whole video and are not time ranges; surfaced separately. */
    val fullVideoLabel: String? = raw.firstOrNull { it.action == "full" }?.category

    val skips: List<YouTubeApiClient.Segment> = mergeSkips(raw.filter { it.action == "skip" })
    private val mutes: List<YouTubeApiClient.Segment> = raw
        .filter { it.action == "mute" && it.endSec - it.startSec >= MIN_LEN }
        .sortedBy { it.startSec }

    val isEmpty: Boolean get() = skips.isEmpty() && mutes.isEmpty()
    val count: Int get() = skips.size + mutes.size

    private val armed = BooleanArray(skips.size) { true }
    private var muted: YouTubeApiClient.Segment? = null

    fun step(posSec: Double): List<Decision> {
        val out = ArrayList<Decision>(2)
        skips.forEachIndexed { i, s ->
            if (posSec < s.startSec - REARM_MARGIN || posSec >= s.endSec) {
                if (posSec < s.startSec - REARM_MARGIN) armed[i] = true
            } else if (armed[i] && posSec >= s.startSec && posSec < s.endSec - TAIL) {
                armed[i] = false
                out += Decision.Seek(s.endSec + SEEK_PAD, s)
            }
        }
        val inMute = mutes.firstOrNull { posSec >= it.startSec && posSec < it.endSec - TAIL }
        if (inMute !== muted) {
            val entered = inMute != null
            muted = inMute
            out += Decision.Mute(inMute, entered)
        }
        return out
    }

    /** Milliseconds to sleep so the next boundary is hit without a fixed fast poll. */
    fun nextDelayMs(posSec: Double): Long {
        val nextStart = (skips.asSequence() + mutes.asSequence())
            .map { it.startSec }
            .filter { it > posSec }
            .minOrNull()
        val inRange = muted != null
        val untilMs = if (nextStart == null) MAX_DELAY else ((nextStart - posSec) * 1000).toLong() - LEAD_MS
        return (if (inRange) minOf(untilMs, 250L) else untilMs).coerceIn(MIN_DELAY, MAX_DELAY)
    }

    private companion object {
        const val MIN_LEN = 0.3
        const val MERGE_GAP = 0.5
        const val TAIL = 0.2
        const val SEEK_PAD = 0.05
        const val REARM_MARGIN = 1.0
        const val LEAD_MS = 60L
        const val MIN_DELAY = 80L
        const val MAX_DELAY = 1_000L

        fun mergeSkips(list: List<YouTubeApiClient.Segment>): List<YouTubeApiClient.Segment> {
            val sorted = list.filter { it.endSec - it.startSec >= MIN_LEN }.sortedBy { it.startSec }
            val out = ArrayList<YouTubeApiClient.Segment>()
            for (s in sorted) {
                val last = out.lastOrNull()
                if (last != null && s.startSec <= last.endSec + MERGE_GAP) {
                    if (s.endSec > last.endSec) out[out.lastIndex] = last.copy(endSec = s.endSec)
                } else {
                    out += s
                }
            }
            return out
        }
    }
}
