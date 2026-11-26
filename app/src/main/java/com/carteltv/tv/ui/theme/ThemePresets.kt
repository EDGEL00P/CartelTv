package com.carteltv.tv.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The house palette: white on a cinematic black stage with a restrained red accent.
 * It lives in an observable [bus] and
 * ThemeSemantics's accent getters read through it, so a change recomposes every reader.
 */
object ThemePresets {

    data class Palette(
        val primary: Color, val glare: Color, val shadow: Color,
    )

    // The Netflix read: a red-carpet accent on the dark stage, replacing the vibrant-blue tvOS look.
    val active = Palette(Color(0xFFE50914), Color(0xFFFF2B36), Color(0xFF7A0A10))

    /** The palette on screen right now. */
    val bus = mutableStateOf(active)

    fun blend(from: Palette, to: Palette, t: Float) = Palette(
        lerp(from.primary, to.primary, t), lerp(from.glare, to.glare, t), lerp(from.shadow, to.shadow, t),
    )

    /** Live (Material You) palette; takes precedence while set.
     *  State, not a plain var — a change must recompose every ThemeSemantics reader. */
    var dynamic: Palette? by mutableStateOf(null)
}
