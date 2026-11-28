package com.carteltv.tv.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlin.math.floor

/**
 * A real QR code for [text] (ZXing via [com.carteltv.tv.core.QrEncoder]): black
 * modules on white with the standard 4-module quiet zone, drawn on whole
 * physical pixels so no module is blurred by fractional scaling. Falls back to
 * a caption only when the payload cannot be encoded at all.
 */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
    cellSize: Dp = 6.dp,
    dark: Color = Color.Black,
    light: Color = Color.White,
) {
    val matrix =
        remember(text) {
            com.carteltv.tv.core.QrEncoder
                .encode(text)
        }
    if (matrix == null) {
        Text(
            "This text is too long for a QR code.",
            fontSize = ThemeSpec.Type.label,
            color = ThemeSemantics.textSecondary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        return
    }
    val n = matrix.size
    val quiet = 4
    val total = n + 2 * quiet
    Canvas(modifier = modifier.size(cellSize * total).semantics { contentDescription = "QR code for pairing" }) {
        // Whole-pixel modules, centred: fractional cells smear modules together
        // and phone cameras cannot read the code off the screen.
        val cs = floor(size.width / total).coerceAtLeast(1f)
        val drawn = cs * total
        val ox = (size.width - drawn) / 2f
        val oy = (size.height - drawn) / 2f
        drawRect(light, topLeft = Offset(ox, oy), size = Size(drawn, drawn))
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (!matrix[r][c]) continue
                drawRect(
                    color = dark,
                    topLeft = Offset(ox + (c + quiet) * cs, oy + (r + quiet) * cs),
                    size = Size(cs, cs),
                )
            }
        }
    }
}
