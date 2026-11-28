package com.carteltv.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.Player
import com.carteltv.tv.ui.player.NowPlayingState
import kotlinx.coroutines.delay

/**
 * Tells the sleep timer what the player is really doing, once a second, for as
 * long as it is composed. [artist] is the channel or show line under the title.
 */
@Composable
fun ReportNowPlaying(player: Player, title: String, artist: String? = null) {
    LaunchedEffect(player, title, artist) {
        while (true) {
            val duration = runCatching { player.duration }.getOrDefault(0L)
            NowPlayingState.set(
                NowPlayingState.State(
                    title = title,
                    artist = artist,
                    playing = runCatching { player.isPlaying }.getOrDefault(false),
                    progress = runCatching {
                        if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else null
                    }.getOrNull(),
                ),
            )
            delay(1_000L)
        }
    }
    // Whoever plays last owns the line: a player that leaves takes its title with it.
    DisposableEffect(player) { onDispose { NowPlayingState.clear() } }
}
