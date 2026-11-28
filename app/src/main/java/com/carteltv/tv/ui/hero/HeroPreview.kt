@file:androidx.media3.common.util.UnstableApi

package com.carteltv.tv.ui.hero

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.ui.player.buildCartelPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The hero's muted video preview, after the pattern Netflix, Disney+ and Max use on their TV home
 * pages: the focused slide plays a short, silent, looping excerpt over its artwork. The stream is
 * prepared only once the slide is active, and a stream that cannot start simply leaves the art.
 */
@Composable
internal fun HeroPreview(
    url: String?,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    if (url.isNullOrBlank() || !AppPreferences.autoTrailers) return
    val context = LocalContext.current
    var ready by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        buildCartelPlayer(context, live = false, audioFocus = false).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    // The artwork stays on screen until the first frame is ready, so a slow or failed stream never flashes black.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                ready = playbackState == Player.STATE_READY
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                ready = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(active) {
        if (active) {
            delay(HERO_PREVIEW_DELAY_MS)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
            // A preview is a taste, not the whole title: loop its first stretch for as long as it has focus.
            while (isActive) {
                delay(HERO_PREVIEW_LOOP_POLL_MS)
                if (player.currentPosition > HERO_PREVIEW_LOOP_MS) player.seekTo(0L)
            }
        } else {
            player.playWhenReady = false
        }
    }
    AndroidView(
        factory = { view ->
            PlayerView(view).apply {
                this.player = player
                useController = false
                controllerAutoShow = false
                isFocusable = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        modifier = modifier.graphicsLayer { alpha = if (ready) 1f else 0f },
    )
}

/** The beat before a focused slide starts playing, so a pass through the hero never buffers. */
private const val HERO_PREVIEW_DELAY_MS = 800L

/** The preview loops its opening stretch rather than playing on. */
private const val HERO_PREVIEW_LOOP_MS = 20_000L
private const val HERO_PREVIEW_LOOP_POLL_MS = 500L
