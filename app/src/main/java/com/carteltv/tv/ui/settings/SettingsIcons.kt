package com.carteltv.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.carteltv.tv.R
import com.carteltv.tv.ui.theme.PhosphorIcon

internal enum class SettingsIcon { PLAYBACK, NETWORK, GRID, TUBE, REC, PERSON, INFO, CLOCK, CODE, MOON, REMOTE, PEOPLE, MIC }

@Composable
internal fun Glyph(kind: SettingsIcon, tint: Color, size: Dp) {
    PhosphorIcon(settingsIcon(kind), tint, size)
}

private fun settingsIcon(kind: SettingsIcon): Int =
    when (kind) {
        SettingsIcon.PLAYBACK -> R.drawable.ph_play_circle
        SettingsIcon.NETWORK -> R.drawable.ph_wifi_high
        SettingsIcon.GRID -> R.drawable.ph_squares_four
        SettingsIcon.TUBE -> R.drawable.ph_youtube_logo
        SettingsIcon.REC -> R.drawable.ph_record
        SettingsIcon.PERSON -> R.drawable.ph_user
        SettingsIcon.INFO -> R.drawable.ph_info
        SettingsIcon.CLOCK -> R.drawable.ph_clock
        SettingsIcon.CODE -> R.drawable.ph_code
        SettingsIcon.MOON -> R.drawable.ph_moon
        SettingsIcon.REMOTE -> R.drawable.ph_game_controller
        SettingsIcon.PEOPLE -> R.drawable.ph_users
        SettingsIcon.MIC -> R.drawable.ph_microphone
    }