package com.carteltv.tv.ui.player

/**
 * Lets one player at a time hold audio focus, for a surface that runs several (multi-view). The
 * previous holder gives focus up before the next one asks, so no player is ever told it lost focus
 * to its own neighbour, which Media3 would answer by pausing it for good.
 */
internal class AudioFocusHandoff {
    /** A player that can take or give up audio focus. */
    fun interface Holder {
        fun holdAudioFocus(hold: Boolean)
    }

    private var holder: Holder? = null

    /** [next] becomes the one holder, whichever order the callers run in. */
    fun claim(next: Holder) {
        val previous = holder
        if (previous === next) return
        holder = next
        previous?.holdAudioFocus(false)
        next.holdAudioFocus(true)
    }

    fun release(previous: Holder) {
        if (holder !== previous) return
        holder = null
        previous.holdAudioFocus(false)
    }
}
