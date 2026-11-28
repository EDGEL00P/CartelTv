package com.carteltv.tv.ui.theme

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusThemeTest {
    @Test fun pointerHoverIsAQuieterStateThanDpadFocus() {
        assertTrue(ThemeSpec.Focus.hoverLift > 1f)
        assertTrue(ThemeSpec.Focus.hoverLift < ThemeSpec.Focus.liftCompact)
        assertTrue(ThemeSpec.Focus.liftCompact <= 1.10f)
        assertTrue(ThemeSpec.Focus.pressed < 1f)
    }

    @Test fun surfaceStatesFormOneTranslucentGlassLadder() {
        assertTrue(ThemeSemantics.surfaceRest.alpha < ThemeSemantics.surfaceFocus.alpha)
        assertTrue(ThemeSemantics.surfaceFocus.alpha < ThemeSemantics.surfaceSelected.alpha)
        assertTrue(ThemeSemantics.surfaceSelected.alpha < ThemeSemantics.surfaceModal.alpha)
        assertNotEquals(ThemeSemantics.surfaceFocus, ThemeSemantics.surfaceSelected)
    }

    @Test fun glassIsActuallyTranslucentWithRealFrost() {
        // The audit regression: 72–98% fills over flat black read as charcoal, not glass.
        assertTrue("resting glass must let the backdrop through", ThemeSemantics.surfaceRest.alpha <= 0.50f)
        assertTrue("focused glass must let the backdrop through", ThemeSemantics.surfaceFocus.alpha <= 0.65f)
        assertTrue("modal glass stays translucent", ThemeSemantics.surfaceModal.alpha <= 0.90f)
        assertTrue("glass needs a real backdrop blur", ThemeSpec.Glass.blurRadius >= 16.dp)
        assertTrue("rim is brightest at the top edge", ThemeSpec.Glass.rimTopAlpha > ThemeSpec.Glass.rimBottomAlpha)
        assertTrue("panes cast a rest shadow", ThemeSpec.Glass.restElevation > 0.dp)
    }

    @Test fun focusAndSelectionStayReadableOnTheLightStage() {
        listOf(
            "rest" to ThemeSemantics.surfaceRest,
            "focus" to ThemeSemantics.surfaceFocus,
            "selected" to ThemeSemantics.surfaceSelected,
        ).forEach { (name, surface) ->
            val composed = lerp(ThemeSemantics.stage, surface, surface.alpha)
            val text = ThemeSemantics.textEmphatic.luminance()
            val background = composed.luminance()
            val contrast = (maxOf(text, background) + 0.05f) / (minOf(text, background) + 0.05f)
            assertTrue("$name surface must keep primary text readable", contrast >= 4.5f)
        }
    }

    @Test fun focusInkHasACrispOutlineAgainstTheFocusedGlass() {
        val focusedGlass = lerp(ThemeSemantics.stage, ThemeSemantics.surfaceFocus, ThemeSemantics.surfaceFocus.alpha)
        val ring = ThemeSemantics.focusRing.luminance()
        val background = focusedGlass.luminance()
        val contrast = (maxOf(ring, background) + 0.05f) / (minOf(ring, background) + 0.05f)
        assertTrue("focus outline must remain visible", contrast >= 4.5f)
    }
}
