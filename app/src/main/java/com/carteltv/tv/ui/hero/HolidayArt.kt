package com.carteltv.tv.ui.hero

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.carteltv.tv.R
import com.carteltv.tv.data.events.HolidayKind

/** The photograph each holiday stands on (Pexels License, see NOTICE). Holidays that share a scene share the picture. */
internal fun holidayPhoto(kind: HolidayKind): Int = when (kind) {
    HolidayKind.CHRISTMAS -> R.drawable.hl_christmas
    HolidayKind.THANKSGIVING -> R.drawable.hl_thanksgiving
    HolidayKind.HALLOWEEN -> R.drawable.hl_halloween
    HolidayKind.NEW_YEAR, HolidayKind.NEW_YEARS_EVE, HolidayKind.NATIONAL_DAY -> R.drawable.hl_fireworks
    HolidayKind.VALENTINE -> R.drawable.hl_valentine
    HolidayKind.ST_PATRICK -> R.drawable.hl_stpatrick
    HolidayKind.EASTER -> R.drawable.hl_easter
    HolidayKind.BONFIRE_NIGHT -> R.drawable.hl_bonfire
    HolidayKind.BOXING_DAY -> R.drawable.hl_boxing
    HolidayKind.REMEMBRANCE -> R.drawable.hl_poppy
}

/**
 * The palette each holiday is washed in. The tint steers the photograph's mood toward the season without
 * drowning it; Remembrance stays neutral out of respect for its subject.
 */
internal fun holidayWash(kind: HolidayKind): Color = when (kind) {
    HolidayKind.CHRISTMAS -> Color(0xFFE85C5C)
    HolidayKind.THANKSGIVING -> Color(0xFFE8A24A)
    HolidayKind.HALLOWEEN -> Color(0xFFA24AE8)
    HolidayKind.NEW_YEAR, HolidayKind.NEW_YEARS_EVE, HolidayKind.NATIONAL_DAY -> Color(0xFF5CA8E8)
    HolidayKind.VALENTINE -> Color(0xFFE85C8A)
    HolidayKind.ST_PATRICK -> Color(0xFF4AE85C)
    HolidayKind.EASTER -> Color(0xFFB08CE8)
    HolidayKind.BONFIRE_NIGHT -> Color(0xFFE87830)
    HolidayKind.BOXING_DAY -> Color(0xFF4AC8E8)
    HolidayKind.REMEMBRANCE -> Color.White
}

/** Softly steers the photograph's highlights and shadows toward the holiday's palette while keeping its detail. */
private fun holidayTint(wash: Color) = ColorFilter.lighting(
    multiply = lerp(Color.White, wash, 0.28f),
    add = Color(red = wash.red * 0.05f, green = wash.green * 0.05f, blue = wash.blue * 0.05f),
)

/**
 * Full-bleed seasonal photograph, tinted in the holiday's palette. A black veil is heaviest along the left where
 * the copy sits and at the foot where the countdown does, keeping the white text readable without washing out
 * the photograph.
 */
@Composable
internal fun HolidayBackdrop(kind: HolidayKind, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier.clipToBounds()) {
        Image(
            painterResource(holidayPhoto(kind)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = holidayTint(holidayWash(kind)),
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.78f), 0.48f to Color.Black.copy(alpha = 0.44f), 1f to Color.Transparent),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.18f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.50f)),
            ),
        )
    }
}
