package com.carteltv.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carteltv.tv.R
import com.carteltv.tv.ui.theme.PhosphorIcon

/** Shared player-control icon vocabulary, used by the player bar and YouTube chrome. */
internal enum class PlayerGlyph {
    PLAY, PAUSE, BACK, FORWARD, SKIP_NEXT, AUDIO, SUBS, SPEED, ASPECT, VOICE, MORE,
    INFO, SOURCES, MULTI, SEARCH, SCHEDULE, PANEL, SYNC, HOME, PERSON, BOOKMARK, BOOKMARK_ON, HISTORY, SETTINGS,
}

/** A player control's mark: its Phosphor icon, tinted from the theme. */
@Composable
internal fun PlayerGlyphIcon(glyph: PlayerGlyph, tint: Color, size: Dp = 24.dp) {
    PhosphorIcon(deckIcon(glyph), tint, size)
}

private fun deckIcon(g: PlayerGlyph): Int =
    when (g) {
        PlayerGlyph.PLAY -> R.drawable.ph_play
        PlayerGlyph.PAUSE -> R.drawable.ph_pause
        PlayerGlyph.BACK -> R.drawable.ph_rewind
        PlayerGlyph.FORWARD -> R.drawable.ph_fast_forward
        PlayerGlyph.SKIP_NEXT -> R.drawable.ph_skip_forward
        PlayerGlyph.AUDIO -> R.drawable.ph_speaker_high
        PlayerGlyph.SUBS -> R.drawable.ph_closed_captioning
        PlayerGlyph.SPEED -> R.drawable.ph_gauge
        PlayerGlyph.ASPECT -> R.drawable.ph_arrows_out
        PlayerGlyph.HISTORY -> R.drawable.ph_clock_counter_clockwise
        PlayerGlyph.SETTINGS -> R.drawable.ph_gear_six
        PlayerGlyph.VOICE -> R.drawable.ph_microphone
        PlayerGlyph.MORE -> R.drawable.ph_dots_three
        PlayerGlyph.INFO -> R.drawable.ph_info
        PlayerGlyph.SOURCES -> R.drawable.ph_stack
        PlayerGlyph.MULTI -> R.drawable.ph_squares_four
        PlayerGlyph.SEARCH -> R.drawable.ph_magnifying_glass
        PlayerGlyph.SCHEDULE -> R.drawable.ph_calendar_blank
        PlayerGlyph.PANEL -> R.drawable.ph_sidebar_simple
        PlayerGlyph.SYNC -> R.drawable.ph_arrows_clockwise
        PlayerGlyph.HOME -> R.drawable.ph_house
        PlayerGlyph.PERSON -> R.drawable.ph_user
        PlayerGlyph.BOOKMARK -> R.drawable.ph_bookmark_simple
        PlayerGlyph.BOOKMARK_ON -> R.drawable.ph_bookmark_simple_fill
    }
