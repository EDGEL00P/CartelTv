package com.carteltv.tv.data.espn

/**
 * Pure prev→now diff for one followed game, LiveMatches-style: the poller hands two
 * snapshots to [diff] and gets the single event worth shouting about, or null when
 * only the clock moved. No Android types; every transition is unit-testable.
 */
object ScoreDifferential {

    enum class Event { KICKOFF, SCORE, FINAL }

    /**
     * One event per poll cycle: kickoff beats a score that lands in the same cycle,
     * and final beats everything. Null when nothing changed, or when only the clock
     * or status detail moved (the caller re-baselines those without alerting).
     */
    fun diff(prev: EspnScoreboardClient.Score?, now: EspnScoreboardClient.Score): Event? {
        if (prev == null) return null
        if (prev.state == "post") return null
        return when {
            prev.state == "pre" && now.state == "in" -> Event.KICKOFF
            now.state == "post" -> Event.FINAL
            prev.scoreA != now.scoreA || prev.scoreB != now.scoreB -> Event.SCORE
            else -> null
        }
    }
}
