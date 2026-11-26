package com.carteltv.tv.engine.stability

/**
 * Stops a TV left playing to nobody: after [limitMs] of playback with no remote input it asks whether the viewer is
 * still there, and ends playback if [graceMs] pass unanswered. Any key answers. Paused time does not count, since
 * a pause is itself input. A [limitMs] of 0 turns the guard off. Pure, so the player only feeds it times.
 */
class ViewerPresenceGuard(private val limitMs: Long, private val graceMs: Long = GRACE_MS) {

    enum class State { OK, ASK, STOP }

    private var lastInputMs = 0L
    private var askedAtMs: Long? = null
    private var started = false

    val asking: Boolean get() = askedAtMs != null

    /** The viewer pressed a key. */
    fun input(nowMs: Long) {
        lastInputMs = nowMs
        askedAtMs = null
        started = true
    }

    /** Seconds the question has left, for the prompt; 0 when none is open. */
    fun secondsLeft(nowMs: Long): Int {
        val asked = askedAtMs ?: return 0
        return ((graceMs - (nowMs - asked) + 999) / 1000).toInt().coerceIn(0, (graceMs / 1000).toInt())
    }

    fun check(nowMs: Long, playing: Boolean): State {
        if (limitMs <= 0L) return State.OK
        if (!started) { input(nowMs); return State.OK }
        val asked = askedAtMs
        if (asked != null) return if (nowMs - asked >= graceMs) State.STOP else State.ASK
        if (!playing) { lastInputMs = nowMs; return State.OK }
        if (nowMs - lastInputMs >= limitMs) {
            askedAtMs = nowMs
            return State.ASK
        }
        return State.OK
    }

    companion object {
        const val GRACE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
    }
}
