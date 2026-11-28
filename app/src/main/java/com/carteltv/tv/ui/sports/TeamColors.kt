package com.carteltv.tv.ui.sports
import androidx.compose.ui.graphics.Color
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.ui.live.hhmm

/** A team plate's two ends: [outer] sits behind the logo, [inner] fades toward the centre of the bug. */
internal data class PlateColors(val outer: Color, val inner: Color)

private const val TOO_DARK = 0.10f
private const val TOO_LIGHT = 0.85f

/** "fb4f14" or "#FB4F14" as a colour; null for anything that is not six hex digits. */
internal fun parseTeamColor(hex: String?): Color? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    val rgb = digits.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

/** Straight sRGB mix; Compose's own lerp blends in a perceptual space, which barely lifts a pure black. */
private fun mix(from: Color, to: Color, t: Float) = Color(
    from.red + (to.red - from.red) * t, from.green + (to.green - from.green) * t, from.blue + (to.blue - from.blue) * t,
)

/**
 * The plate colours for a team: its brand colour, falling back to the alternate when the brand colour
 * would vanish into the dark bug (black) or glare (white), and nudged when neither works.
 */
internal fun plateColors(color: String?, alternate: String?, fallback: Color): PlateColors {
    val primary = parseTeamColor(color)
    val alt = parseTeamColor(alternate)
    var base = primary ?: alt ?: fallback
    if (luminance(base) < TOO_DARK) {
        base = alt?.takeIf { luminance(it) in TOO_DARK..TOO_LIGHT } ?: mix(base, Color.White, 0.30f)
    } else if (luminance(base) > TOO_LIGHT) {
        base = alt?.takeIf { luminance(it) in TOO_DARK..TOO_LIGHT } ?: mix(base, Color.Black, 0.40f)
    }
    return PlateColors(outer = base, inner = mix(base, Color.Black, 0.55f))
}

private fun ordinal(n: Int) = when (n) {
    1 -> "1ST"; 2 -> "2ND"; 3 -> "3RD"; else -> "${n}TH"
}

private val RUNNING_CLOCK = Regex("""^\d{1,2}:\d{2}(?:\.\d)? - \d+(?:st|nd|rd|th)(?: OT)?$""", RegexOption.IGNORE_CASE)

/**
 * What the bug's clock pill reads: "2ND 9:33" while a period clock runs, otherwise the feed's own status line
 * ("HALFTIME", "END OF 1ST", "85'") so a stopped game never claims a ticking clock.
 */
internal fun clockLabel(game: EspnScoreboardClient.Score): String {
    val status = game.shortDetail
    val clock = game.clock
    val running = clock != null && game.period > 0 && (status == null || RUNNING_CLOCK.matches(status))
    return if (running) "${ordinal(game.period)} $clock" else (status ?: game.detail).uppercase()
}

/**
 * The game's state in words for tiles and shelves: "LIVE 2ND 9:33" while running, "LIVE IN 12 MIN"
 * once a game is close, the start clock further out, and the feed's final word when done.
 */
internal fun stateLabel(game: EspnScoreboardClient.Score, nowMs: Long): String = when (game.state) {
    "in" -> "LIVE ${clockLabel(game)}"
    "pre" -> when {
        game.startMs <= 0L -> ""
        game.startMs - nowMs in 0..(60 * 60_000L) -> "LIVE IN ${((game.startMs - nowMs + 59_999L) / 60_000L).coerceAtLeast(1)} MIN"
        else -> "STARTS ${clockOf(game.startMs)}"
    }
    else -> clockLabel(game)
}

/** "HH:mm" wall clock for a kickoff, empty when the feed gave no start time. */
internal fun clockOf(ms: Long): String =
    if (ms <= 0L) "" else hhmm(ms)