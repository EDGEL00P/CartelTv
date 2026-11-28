package com.carteltv.tv.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassSurface

/** Tight broadcast geometry shared by rows, tabs and buttons; the material is [glassSurface]. */
internal object GlassGeometry {
    val RowShape = RoundedCornerShape(ThemeSpec.Card.corner)
    val TabShape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
    val CapsuleShape = RoundedCornerShape(ThemeSpec.Card.pill)
    val ButtonShape = RoundedCornerShape(ThemeSpec.Card.cornerSm)

    val RowLift: Float = ThemeSpec.Focus.liftCompact
    val RowRing: Dp = ThemeSpec.Focus.ringWidth
}

/** A list row: the glass surface with a small lift and a solid ring while focused, since rows are dense. */
@Composable
internal fun Modifier.glassRow(focused: Boolean, pressed: Boolean = false): Modifier =
    this.focusLift(focusScaleState(focused, GlassGeometry.RowLift, pressed))
        .glassSurface(focused, GlassGeometry.RowShape, ringWidth = GlassGeometry.RowRing)

/** Primary text colour for content sitting on a [glassRow]. */
internal fun glassRowText(focused: Boolean): Color = ThemeSemantics.textEmphatic

/** Secondary text colour for content sitting on a [glassRow]. */
internal fun glassRowDim(focused: Boolean): Color = if (focused) ThemeSemantics.textEmphatic.copy(alpha = 0.78f) else ThemeSemantics.textSecondary
