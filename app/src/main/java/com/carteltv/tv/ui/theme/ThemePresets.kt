package com.carteltv.tv.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Accent-led themes: the cinematic layout and neutral resting surfaces stay unchanged. */
object ThemePresets {

    data class Palette(
        val primary: Color,
        val glare: Color,
        val shadow: Color,
        val onAccent: Color = Color.White,
    )

    enum class Preset(val id: String, val label: String, val description: String, val palette: Palette) {
        CARTEL(
            "cartel", "Cartel Red", "The classic cinematic red",
            Palette(Color(0xFFE50914), Color(0xFFFF2B36), Color(0xFF7A0A10)),
        ),
        CYBERPUNK(
            "cyberpunk", "Cyberpunk", "Neon magenta and electric cyan",
            Palette(Color(0xFFFF2BD6), Color(0xFF00F5FF), Color(0xFF7209B7), Color(0xFF090014)),
        ),
        AURORA(
            "aurora", "Aurora", "Cool mint and northern-lights teal",
            Palette(Color(0xFF5EEAD4), Color(0xFFA7F3D0), Color(0xFF0F766E), Color(0xFF03211D)),
        ),
        OCEAN(
            "ocean", "Ocean", "Deep-sea blue and glacial cyan",
            Palette(Color(0xFF38BDF8), Color(0xFF67E8F9), Color(0xFF1D4ED8), Color(0xFF031725)),
        ),
        SUNSET(
            "sunset", "Sunset", "Warm coral, amber and ember red",
            Palette(Color(0xFFFF6B35), Color(0xFFFFC857), Color(0xFF9B2226), Color(0xFF260A00)),
        ),
        MATRIX(
            "matrix", "Matrix", "Terminal green on cinematic black",
            Palette(Color(0xFF00FF41), Color(0xFFA3FFB0), Color(0xFF008F11), Color(0xFF001205)),
        ),
        ULTRAVIOLET(
            "ultraviolet", "Ultraviolet", "Electric violet and soft lavender",
            Palette(Color(0xFF7C3AED), Color(0xFFC4B5FD), Color(0xFF4C1D95)),
        ),
    }

    /** The palette on screen right now. Updating this repaints every ThemeSemantics reader. */
    val bus = mutableStateOf(Preset.CARTEL.palette)

    fun fromId(id: String?): Preset = Preset.entries.firstOrNull { it.id == id } ?: Preset.CARTEL

    /** Applies a saved or newly selected preset and returns the resolved preset. */
    fun select(id: String?): Preset = fromId(id).also { bus.value = it.palette }

    /** Accessibility preference: keeps preset hues but makes glass surfaces mostly solid. */
    var reduceTransparency: Boolean by mutableStateOf(false)

    /** System ANIMATOR_DURATION_SCALE == 0, read once at startup by ThemeAccessibility.install. */
    var systemAnimationsRemoved: Boolean by mutableStateOf(false)

}
