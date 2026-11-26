package com.carteltv.tv.ui.player
import androidx.compose.runtime.mutableStateOf

/**
 * In-app playback state for CartelTV's own players (movies, YouTube, music).
 * The sleep timer reads this even when no MediaSession is published.
 */
object NowPlayingState {
    data class State(
        val title: String,
        val artist: String? = null,
        val playing: Boolean = true,
        val progress: Float? = null,
        val pkg: String = "com.carteltv.tv",
    )

    val current = mutableStateOf<State?>(null)

    fun set(state: State?) { current.value = state }
    fun clear() { current.value = null }

    /** True while a CartelTV player reports active playback (used by the sleep timer). */
    fun isPlaying(): Boolean = current.value?.playing == true
}
