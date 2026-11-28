package com.carteltv.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * One broadcaster's on-air identity: a dark [canvasPrimary] ground under its three brand tones.
 * [signature] fills the stage like a studio wall, [accentPrimary] carries selection, LIVE and focus, and
 * [highlight] is the third tone its graphics use for pinstripes and key light.
 */
data class BroadcastTheme(
    val id: String,
    val label: String,
    val canvasPrimary: Color,
    val signature: Color,
    val accentPrimary: Color,
    val highlight: Color,
) {
    val focusRingTint: Color get() = Color(0xFFF7F8FA)
    val focusGlowTint: Color get() = accentPrimary.copy(alpha = 0.38f)

    /** The accent lifted toward white, for accent-coloured text on the canvas: every preset clears 5:1. */
    val accentBright: Color get() = lerp(accentPrimary, Color.White, 0.30f)
    val accentShadow: Color get() = lerp(canvasPrimary, accentPrimary, 0.38f)

    /** Glass and ink tinted with the signature hue: see [NeutralTones]. */
    val neutrals: NeutralTones = NeutralTones(signature)

    /** Black or white, whichever contrasts more with the accent: the two tie at relative luminance ≈0.179. */
    val onAccent: Color get() = if (accentPrimary.luminance() > 0.179f) Color.Black else Color.White
}

/**
 * A preset's neutral ladder, built the way Radix Colors and Material 3 build dark surfaces: greys carry a little of
 * the brand hue. Each step keeps the OKLCH lightness of the house neutral it replaces (Oklab L is the cube root of
 * luminance for a grey), so text contrast and glass depth are the same on every preset and only the hue changes.
 * Chroma is capped by the signature's own, so a grey brand (ESPN graphite) stays grey.
 */
class NeutralTones(signature: Color) {
    private val hue: Float
    private val brandChroma: Float

    init {
        val lab = signature.convert(ColorSpaces.Oklab)
        hue = atan2(lab.blue, lab.green)
        brandChroma = hypot(lab.green, lab.blue)
    }

    private fun tone(lightness: Float, maxChroma: Float, alpha: Float = 1f): Color {
        val chroma = min(brandChroma, maxChroma)
        return Color(lightness, chroma * cos(hue), chroma * sin(hue), alpha, ColorSpaces.Oklab).convert(ColorSpaces.Srgb)
    }

    val glass = tone(0.191f, 0.030f, 0.42f) // resting glass (was #141414 at 42%)
    val glassFocus = tone(0.218f, 0.030f, 0.55f) // focused glass (#1A1A1A at 55%)
    val glassStrong = tone(0.226f, 0.030f, 0.62f) // selected and raised glass (#1C1C1C at 62%)
    val modal = tone(0.134f, 0.025f, 0.72f) // modal glass (#080808 at 72%)
    val ink2 = tone(0.767f, 0.016f) // secondary text (#B3B3B3)
    val ink3 = tone(0.600f, 0.020f) // tertiary text (#808080)
    val hairline = tone(0.960f, 0.030f, 0.22f) // glass rim (white at 22%)
}

/*
 * Palettes are the broadcasters' published or measured brand colours; canvases are each brand's own dark ground.
 * ESPN: ESPN red #DA020E and graphite #48494A (Mobbin, ESPN app). FOX Sports: FOX blue #0059B5 and navy #081F57
 * (foxsports.com). Sky Sports: red #DF1A19 and blue #002675 (BrandColorCode). TNT Sports: neon magenta #FF28FF on
 * #14232D (Brandfetch; DixonBaxi 2023 identity). DAZN: neon yellow lead colour (How Now rebrand; #F8FC00 is matched by
 * eye, not published) on #080E12 / #242D33 (dazn.com).
 * beIN SPORTS: purple #5C2D91, lemon #FFF967, ground #050617 (Brandfetch, beIN SPORTS USA), violet red #FA407F
 * (Brandfetch, beIN SPORTS Türkiye). Sky (red/white/blue, BrandColorCode) and TNT (#FF28FF/#14232D/#FFFFFF, Brandfetch)
 * publish white as their third colour; ESPN, FOX ("black, white and FOX blue") and DAZN set white type on air, so their
 * third tone is white as well.
 */
object BroadcastThemes {
    val ESPN = BroadcastTheme("espn", "ESPN", Color(0xFF0A0B0D), Color(0xFF48494A), Color(0xFFDA020E), Color.White)
    val FOX_SPORTS = BroadcastTheme("fox_sports", "FOX Sports", Color(0xFF050B17), Color(0xFF081F57), Color(0xFF0059B5), Color.White)
    val SKY_SPORTS = BroadcastTheme("sky_sports", "Sky Sports", Color(0xFF000B24), Color(0xFF002675), Color(0xFFDF1A19), Color.White)
    val TNT_SPORTS = BroadcastTheme("tnt_sports", "TNT Sports", Color(0xFF0A1218), Color(0xFF14232D), Color(0xFFFF28FF), Color.White)
    val DAZN = BroadcastTheme("dazn", "DAZN", Color(0xFF080E12), Color(0xFF242D33), Color(0xFFF8FC00), Color.White)
    val BEIN_SPORTS = BroadcastTheme("bein_sports", "beIN SPORTS", Color(0xFF050617), Color(0xFF5C2D91), Color(0xFFFFF967), Color(0xFFFA407F))

    val presets = listOf(ESPN, FOX_SPORTS, SKY_SPORTS, TNT_SPORTS, DAZN, BEIN_SPORTS)
    val current = mutableStateOf(ESPN)

    fun fromId(id: String?): BroadcastTheme = presets.firstOrNull { it.id == id } ?: ESPN

    fun select(id: String?): BroadcastTheme = fromId(id).also { current.value = it }
}

val LocalBroadcastTheme = staticCompositionLocalOf { BroadcastThemes.ESPN }

@Composable
fun BroadcastThemeProvider(
    theme: BroadcastTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBroadcastTheme provides theme, content = content)
}
