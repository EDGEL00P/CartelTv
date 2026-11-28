package com.carteltv.tv.ui.components
import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.compose.foundation.layout.Box
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.sp

private const val SHRINK_STEP = 0.92f

/** 10-foot floor: fitted text never lands below 16sp, whatever the caller's minScale allows. */
private const val MIN_FITTED_SP = 16f

/**
 * One line of text that shrinks until it fits the width it is given, never below [minScale] of [fontSize]
 * and never below the 16sp floor; only then does it ellipsize. Use it wherever text sits in a box whose
 * size the screen decides.
 */
@Composable
internal fun AutoSizeText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = ThemeSpec.Font.body,
    fontStyle: FontStyle? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    fontFeatureSettings: String? = null,
    textAlign: TextAlign? = null,
    minScale: Float = 0.55f,
) {
    // A clock's digits are all the same width, so its fitted size holds as it ticks; any other text is fitted afresh.
    val key: Any = if (text.all { it.isDigit() || it == ':' || it == ' ' || it == '-' }) text.length else text
    var scale by remember(key, fontSize) { mutableFloatStateOf(1f) }
    val size = (fontSize.value * scale).sp.takeIf { fontSize.type == TextUnitType.Sp } ?: fontSize
    val floorScale = if (fontSize.type == TextUnitType.Sp) (MIN_FITTED_SP / fontSize.value).coerceIn(0f, 1f) else 0f
    val effectiveMin = minScale.coerceAtLeast(floorScale)
    Box(modifier) {
        Text(
            text, fontSize = size, color = color, fontWeight = fontWeight, fontFamily = fontFamily, fontStyle = fontStyle,
            letterSpacing = letterSpacing, style = TextStyle(fontFeatureSettings = fontFeatureSettings), textAlign = textAlign, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.hasVisualOverflow && scale > effectiveMin) scale = (scale * SHRINK_STEP).coerceAtLeast(effectiveMin) },
        )
    }
}
