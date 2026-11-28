package com.carteltv.tv.ui.player

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.media3.common.C
import androidx.media3.common.Player
import com.carteltv.tv.engine.playback.SeekAccumulator
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.core.suspendRunCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The deck's state: A shows the video only, B the bar with its transport row, C the bar plus the
 * settings tiles. The host owns it so that it outlives a rebuilt player.
 */
@Stable
class PlayerControlState {
    var value by mutableStateOf(HIDDEN)
        internal set

    companion object {
        const val HIDDEN = 'A'
        const val BAR = 'B'
        const val TILES = 'C'
    }
}

/** What a rebuilt player picks up from the one it replaces. Renderers are fixed when a player is built. */
data class PlayerHandover(val positionMs: Long, val playing: Boolean)

/** The optional half of the remote's contract. A host sets only what its surface has. */
@Stable
class PlayerHost {
    /** First look at every key down. True keeps the key, for a prompt that owns the screen. */
    var intercept: (KeyEvent) -> Boolean = { false }

    /** Next and previous item for the media keys, and for channel keys on live. Null when there is none. */
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null

    /** OK while the deck is hidden. True keeps the key (skip the intro); false shows the deck. */
    var onOk: () -> Boolean = { false }

    /** After any key the remote did not have to ignore, such as waving off a title card. */
    var onAnyKey: () -> Unit = {}

    var upNextVisible: () -> Boolean = { false }
    var onCancelUpNext: () -> Unit = {}

    /** True while the error card is up and owns the remote. */
    var errorUp: () -> Boolean = { false }

    /** The frame for the seek HUD at the pending target. */
    var seekThumb: () -> Bitmap? = { null }

    /** Volume step: direction -1 down, +1 up. Return true if handled. */
    var onVolume: (Int) -> Boolean = { false }

    /** Mute toggle. Return true if handled. */
    var onMute: () -> Boolean = { false }

    /** Settings panel. Return true if handled. */
    var onSettings: () -> Boolean = { false }

    /** Info panel. Return true if handled. */
    var onInfo: () -> Boolean = { false }

    /** A digit key, for typing a channel number. Null when the surface has no channels. */
    var onDigit: ((Int) -> Boolean)? = null

    /** The remote's Last key, back to the channel before. Null when there is none to go back to. */
    var onLast: (() -> Boolean)? = null

    /** True while a host panel over the picture, such as the live channel list, owns the D-pad and Back. */
    var sheetUp: () -> Boolean = { false }

    /**
     * Live TV only. Set, they make the idle D-pad a TV remote: up/down change channel, OK opens the
     * channel list, left the guide and right the deck. True when the panel opened.
     */
    var onChannels: (() -> Boolean)? = null
    var onGuide: (() -> Boolean)? = null
}

private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

/** What the deck can do; [PlayerOverlay] registers it with the remote for as long as it is up. */
interface PlayerControl {
    /** True while the track picker or the stream-info card owns input. */
    val modal: Boolean

    /** Hidden goes to the bar; the other states stay. */
    fun show()

    /** C to B, B to hidden, hidden closes the player. */
    fun back()
    fun up()
    fun down()

    /** Shown hides, hidden shows. */
    fun toggle()

    /** Any key activity keeps the deck from auto-hiding. */
    fun touch()

    /** Focus back onto the deck after one of its dialogs closed. */
    fun refocus()
}

/**
 * The remote's contract for a player surface, in one place. The surface calls [rememberPlayerRemote]
 * and puts [playerSurface] on its focusable root; the deck, the host's cards and the Activity's MENU
 * key all go through here.
 *
 * Deck hidden: play/pause keys act at once; FF/RW and left/right take the seek rule; channel keys
 * change item on live and seek on VOD; next/previous change item through the host; OK, up and down
 * show the deck; Back closes. Live TV with a channel list instead zaps on up/down, opens the list
 * on OK and the guide on left; rewind stays on the media keys. Deck shown: the focused chip takes the D-pad and OK, Back steps the
 * deck back, the media keys keep working, and up/down that no chip used move between bar and tiles.
 * While the Up Next card is up, OK and the vertical keys belong to its buttons and Back cancels it.
 */
@Stable
class PlayerRemote internal constructor(
    val player: Player,
    /** True while a live stream is playing. */
    val live: () -> Boolean,
    val deck: PlayerControlState,
    val onClose: () -> Unit,
    internal val host: PlayerHost,
) {
    internal val seeker = SeekAccumulator()
    internal val surface = FocusRequester()

    /** Set by the overlay for as long as it is up. */
    internal var control by mutableStateOf<PlayerControl?>(null)

    /** True while the surface, or something in it, has focus. */
    internal var hasFocus by mutableStateOf(false)
    internal var surfaceFocusTick by mutableIntStateOf(0)

    /** Where the pending seek will land, or null when none is pending. */
    var seekTarget by mutableStateOf<Long?>(null)
        private set

    /** Live only: how far behind the live edge the last left/right press found us, or [AT_LIVE_EDGE]. */
    var liveOffset by mutableStateOf<Long?>(null)
        private set

    /** The surface takes focus back, for after a card that held it went away. */
    fun focusSurface() { surfaceFocusTick++ }

    /** Ends a finished run of seek keys. */
    internal fun commitSeek() {
        seeker.commit()?.let { player.seekTo(it) }
        seekTarget = null
    }

    /** The live offset pill goes away on its own. */
    internal fun clearLiveOffset() { liveOffset = null }

    internal fun preview(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        if (host.intercept(ev)) return true
        // The error card and the deck's own dialogs own the remote while they are up; transport still answers.
        if (host.errorUp() || control?.modal == true || host.sheetUp()) return playPauseKey(player, ev.key)
        control?.touch()
        host.onAnyKey()
        val idle = deck.value == PlayerControlState.HIDDEN
        val card = idle && host.upNextVisible()
        // Live TV with a channel list: the idle D-pad zaps and opens the list and guide, as a TV remote does.
        val tv = idle && !card && live() && host.onChannels != null
        val used = when (ev.key) {
            Key.Back -> if (card) { host.onCancelUpNext(); true } else { control?.back(); true }
            // With the Up Next card up, OK and the vertical keys belong to its buttons.
            Key.DirectionUp -> when {
                card -> false
                tv -> { changeItem(1); true }
                idle -> { host.onVolume(1) || run { control?.show(); true } }
                else -> false
            }
            Key.DirectionDown -> when {
                card -> false
                tv -> { changeItem(-1); true }
                idle -> { host.onVolume(-1) || run { control?.show(); true } }
                else -> false
            }
            Key.DirectionLeft -> when {
                tv && host.onGuide?.invoke() == true -> true
                idle -> { seek(-1); true }
                else -> false
            }
            Key.DirectionRight -> when {
                tv -> { control?.show(); true }
                idle -> { seek(1); true }
                else -> false
            }
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> when {
                !idle || card -> false
                // OK jumps past the intro, otherwise it brings the compact deck up.
                host.onOk() -> true
                tv && host.onChannels?.invoke() == true -> true
                else -> { control?.show(); true }
            }
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> playPauseKey(player, ev.key)
            Key.MediaFastForward -> { seek(1); true }
            Key.MediaRewind -> { seek(-1); true }
            Key.ChannelUp -> { channel(1); true }
            Key.ChannelDown -> { channel(-1); true }
            Key.MediaNext -> { changeItem(1); true }
            Key.MediaPrevious -> { changeItem(-1); true }
            Key.M -> host.onMute()
            Key.S -> host.onSettings()
            Key.I -> host.onInfo()
            Key(android.view.KeyEvent.KEYCODE_LAST_CHANNEL) -> host.onLast?.invoke() ?: false
            else -> digitOf(ev.key)?.let { host.onDigit?.invoke(it) } ?: false
        }
        // The host hides its Up Next card while a seek is pending; the card's focused button leaves with it.
        if (card && used && seekTarget != null) focusSurface()
        return used
    }

    /** The keys that come back up from the focused chip, having found no control that used them. */
    internal fun bubble(ev: KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown || host.errorUp() || control?.modal == true || host.sheetUp()) return false
        return when (ev.key) {
            Key.DirectionUp -> { control?.up(); true }
            Key.DirectionDown -> { control?.down(); true }
            else -> false
        }
    }

    /**
     * The one seek rule. A VOD press adds up in [SeekAccumulator] and commits once the keys go quiet.
     * Live shows where the edge is: left reports the offset, forward returns to live when behind it.
     */
    private fun seek(direction: Int) {
        if (!live()) {
            seekTarget = seeker.press(direction, SystemClock.elapsedRealtime(), player.currentPosition, player.duration)
            return
        }
        if (direction < 0) {
            player.seekTo(((player.currentPosition ?: 0L) - 10_000L).coerceAtLeast(0L))
            liveOffset = runCatching { player.currentLiveOffset }.getOrDefault(C.TIME_UNSET)
            if (liveOffset == C.TIME_UNSET || (liveOffset ?: 0L) <= LIVE_EDGE_MS) liveOffset = AT_LIVE_EDGE
            return
        }
        val offset = runCatching { player.currentLiveOffset }.getOrDefault(C.TIME_UNSET)
        if (offset != C.TIME_UNSET && offset > LIVE_EDGE_MS) {
            player.seekToDefaultPosition()
            liveOffset = null
        } else {
            liveOffset = if (offset == C.TIME_UNSET || offset <= LIVE_EDGE_MS) AT_LIVE_EDGE else offset
        }
    }

    /** Channel keys change item on live and seek on VOD. */
    private fun channel(direction: Int) {
        if (live()) changeItem(direction) else seek(direction)
    }

    private fun changeItem(direction: Int) {
        if (direction > 0) host.onNext?.invoke() else host.onPrevious?.invoke()
    }

    companion object {
        /** Closer than this to the live edge counts as watching live. */
        private const val LIVE_EDGE_MS = 5_000L

        /** [liveOffset] while at the live edge. */
        const val AT_LIVE_EDGE = -1L

        internal const val COMMIT_POLL_MS = 100L
        internal const val OFFSET_PILL_MS = 2_500L

        /** The player surface on screen. Only one can own the remote at a time. */
        internal var active: PlayerRemote? = null

        /**
         * MENU for the Activity that shows a player: the deck appears, or goes away, and playback is
         * never left. False when no player is up, so the shell may treat MENU as its own.
         */
        @JvmStatic
        fun toggleActiveDeck(): Boolean {
            active?.control?.toggle()
            return active != null
        }
    }
}

/** Play/pause keys act on intent rather than on isPlaying, so Pause also sticks while the picture buffers. */
internal fun playPauseKey(player: Player, key: Key): Boolean {
    val play = when (key) {
        Key.MediaPlayPause -> !player.playWhenReady
        Key.MediaPlay -> true
        Key.MediaPause -> false
        else -> return false
    }
    if (play) player.play() else player.pause()
    return true
}

/** The focusable root of a player surface: every key its focused control does not use goes through [remote]. */
internal fun Modifier.playerSurface(remote: PlayerRemote): Modifier = this
    .onFocusChanged { remote.hasFocus = it.hasFocus }
    .focusRequester(remote.surface)
    .onPreviewKeyEvent(remote::preview)
    .onKeyEvent(remote::bubble)
    .focusable()

@Composable
fun rememberPlayerRemote(
    player: Player,
    live: () -> Boolean,
    deck: PlayerControlState = remember { PlayerControlState() },
    onClose: () -> Unit,
    setup: PlayerHost.() -> Unit = {},
): PlayerRemote {
    val remote = remember(player, deck) { PlayerRemote(player, live, deck, onClose, PlayerHost().also(setup)) }
    val host = remote.host

    // A run of seek keys ends in one seek once they have been quiet for SeekAccumulator.SETTLE_MS.
    LaunchedEffect(remote) {
        snapshotFlow { remote.seekTarget != null }.collectLatest { pending ->
            if (!pending) return@collectLatest
            while (true) {
                if (remote.seeker.due(SystemClock.elapsedRealtime())) {
                    remote.commitSeek()
                    break
                }
                delay(PlayerRemote.COMMIT_POLL_MS)
            }
        }
    }

    // The live offset pill answers one press and then goes away.
    LaunchedEffect(remote, remote.liveOffset != null) {
        if (remote.liveOffset != null) {
            delay(PlayerRemote.OFFSET_PILL_MS)
            remote.clearLiveOffset()
        }
    }

    val shown = remote.deck.value != PlayerControlState.HIDDEN
    val covered = remote.control?.modal == true || host.upNextVisible() || host.errorUp() || host.sheetUp()

    // Key events only reach a focusable that has focus: at start, whenever asked, and each time the
    // deck hides, the surface takes it.
    LaunchedEffect(remote, remote.surfaceFocusTick, shown) {
        if (shown) return@LaunchedEffect
        suspendRunCatching { remote.surface.awaitFocus() }
    }

    // Something that held focus inside the surface can leave at once, when a card or the track picker
    // closes. Keys would stop arriving, so focus goes back to the surface, or to the deck while it stays up.
    LaunchedEffect(remote, shown, covered) {
        if (covered) return@LaunchedEffect
        val held = remote.hasFocus
        withFrameNanos { }
        if (held && !remote.hasFocus) {
            if (shown) remote.control?.refocus()
            else suspendRunCatching { remote.surface.awaitFocus() }
        }
    }

    DisposableEffect(remote) {
        PlayerRemote.active = remote
        onDispose { if (PlayerRemote.active === remote) PlayerRemote.active = null }
    }
    return remote
}
