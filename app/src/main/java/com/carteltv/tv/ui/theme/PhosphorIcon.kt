package com.carteltv.tv.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/**
 * One Phosphor icon (MIT, phosphor-icons/core v2.0.8) as a vector drawable, tinted from the theme.
 * Decorative by default ([label] null); pass a label to name the glyph for TalkBack.
 */
@Composable
fun PhosphorIcon(@DrawableRes id: Int, tint: Color, size: Dp, modifier: Modifier = Modifier, label: String? = null) {
    Image(painterResource(id), contentDescription = label, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}