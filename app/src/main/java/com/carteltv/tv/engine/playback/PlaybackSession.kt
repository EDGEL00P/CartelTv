package com.carteltv.tv.engine.playback

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * Publishes the in-app player as a Media3 system media session so remotes, Bluetooth controls and
 * assistants can show and drive it: the launcher card carries the item's [androidx.media3.common.MediaMetadata]
 * and deep-links back to the hosting activity. Remote commands reach the player only through the
 * [ForwardingPlayer] below, so a remote stop closes the player (as on the platform session this
 * replaces) and live streams keep advertising no seeking. Call [release] when the player is released.
 */
@UnstableApi
class PlaybackSession(
    context: Context,
    private val player: Player,
    private val isLive: Boolean,
    onStop: () -> Unit,
) {
    // Only session-originated commands arrive on this wrapper: the UI keeps the raw player, so
    // lifecycle calls such as ON_STOP's player.stop() never masquerade as a remote stop.
    private val commandPlayer = object : ForwardingPlayer(player) {
        override fun stop() = onStop()
        override fun seekTo(positionMs: Long) {
            if (canSeek()) super.seekTo(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (canSeek()) super.seekTo(mediaItemIndex, positionMs)
        }

        override fun seekBack() {
            if (canSeek()) super.seekBack()
        }

        override fun seekForward() {
            if (canSeek()) super.seekForward()
        }
    }

    private val session = MediaSession.Builder(context, commandPlayer)
        .apply { sessionActivity(context)?.let { setSessionActivity(it) } }
        .build()

    private fun canSeek() = !isLive && player.isCurrentMediaItemSeekable

    /** Opens the app at the activity hosting playback, without disturbing whatever sits on top of it. */
    private fun sessionActivity(context: Context): PendingIntent? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) break
            c = c.baseContext
        }
        val activity = c as? Activity ?: return null
        val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            activity,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun release() {
        session.release()
    }
}
