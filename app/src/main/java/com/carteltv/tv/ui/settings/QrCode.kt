package com.carteltv.tv.ui.settings
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.ThemeSemantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a QR code for [text] using the in-house [com.carteltv.tv.core.QrEncoder].
 * Falls back to a caption when the payload is too long for the supported versions.
 */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
    cellSize: Dp = 6.dp,
    dark: Color = ThemeSemantics.textEmphatic,
    light: Color = Color.White,
) {
    val matrix = com.carteltv.tv.core.QrEncoder.encode(text)
    if (matrix == null) {
        Text("This text is too long for a QR code.", fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        return
    }
    val n = matrix.size
    val quiet = 4
    val total = n + 2 * quiet
    Canvas(modifier = modifier.size(cellSize * total).semantics { contentDescription = "QR code for pairing" }) {
        val cs = size.width / total
        drawRect(light, size = size)
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (!matrix[r][c]) continue
                drawRect(
                    color = dark,
                    topLeft = Offset((c + quiet) * cs, (r + quiet) * cs),
                    size = Size(cs, cs),
                )
            }
        }
    }
}
