package com.carteltv.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePresetsTest {
    @After fun resetTheme() {
        ThemePresets.reduceTransparency = false
        ThemePresets.systemAnimationsRemoved = false
        ThemePresets.select(ThemePresets.Preset.CARTEL.id)
    }

    @Test fun presetsHaveStableUniqueIdsAndDistinctColors() {
        val presets = ThemePresets.Preset.entries
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        assertEquals(presets.size, presets.map { it.palette.primary }.toSet().size)
        presets.zipWithNext().forEach { (a, b) -> assertNotEquals(a.palette, b.palette) }
    }

    @Test fun accentTextIsReadableForEveryPreset() {
        ThemePresets.Preset.entries.forEach { preset ->
            val foreground = preset.palette.onAccent.luminance()
            val background = preset.palette.primary.luminance()
            val contrast = (maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f)
            assertTrue("${preset.label} accent text contrast was $contrast", contrast >= 4.5f)
        }
    }

    @Test fun selectionUpdatesTheLivePaletteImmediately() {
        val selected = ThemePresets.select(ThemePresets.Preset.CYBERPUNK.id)
        assertEquals(ThemePresets.Preset.CYBERPUNK, selected)
        assertEquals(selected.palette, ThemePresets.bus.value)
        assertEquals(selected.palette.primary, ThemeSemantics.accent)
        assertEquals(selected.palette.primary, ThemeSemantics.live)
        assertEquals(selected.palette.onAccent, ThemeSemantics.textInverse)
    }

    @Test fun presetsTintEveryGlassTierAndLeaveTheStageNeutral() {
        ThemePresets.select(ThemePresets.Preset.CARTEL.id)
        val stage = ThemeSemantics.stage
        val glass = ThemeSemantics.surfaceRest
        val cartelFocus = ThemeSemantics.surfaceFocus

        ThemePresets.select(ThemePresets.Preset.MATRIX.id)
        assertEquals(ThemePresets.Preset.MATRIX.palette.primary, ThemeSemantics.accent)
        assertEquals(stage, ThemeSemantics.stage)
        // The preset lives in the material: resting glass changes hue, not only focus.
        assertNotEquals(glass, ThemeSemantics.surfaceRest)
        assertNotEquals(cartelFocus, ThemeSemantics.surfaceFocus)
        // Tinting never changes how much light the glass lets through.
        assertEquals(glass.alpha, ThemeSemantics.surfaceRest.alpha, 0.001f)
    }

    @Test fun glassKeepsTextReadableForEveryPreset() {
        ThemePresets.Preset.entries.forEach { preset ->
            ThemePresets.select(preset.id)
            val brightestAmbient = lerp(ThemeSemantics.stage, Color.White, 0.32f)
            listOf("stage" to ThemeSemantics.stage, "brightest ambient" to brightestAmbient).forEach { (backdropName, backdrop) ->
                listOf(ThemeSemantics.surfaceRest, ThemeSemantics.surfaceFocus, ThemeSemantics.surfaceSelected).forEach { surface ->
                    val background = lerp(backdrop, surface, surface.alpha).luminance()
                    val text = ThemeSemantics.textEmphatic.luminance()
                    val contrast = (maxOf(text, background) + 0.05f) / (minOf(text, background) + 0.05f)
                    assertTrue("${preset.label} glass over $backdropName contrast was $contrast", contrast >= 4.5f)
                }
            }
        }
    }

    @Test fun reduceTransparencyMakesGlassSolidWithoutChangingItsColor() {
        val translucent = ThemeSemantics.surfaceRest
        ThemePresets.reduceTransparency = true
        val solid = ThemeSemantics.surfaceRest

        assertTrue(solid.alpha > translucent.alpha)
        assertTrue(solid.alpha >= 0.96f)
        assertEquals(translucent.copy(alpha = solid.alpha), solid)
    }

    @Test fun missingOrUnknownSavedIdsFallBackToCartel() {
        assertEquals(ThemePresets.Preset.CARTEL, ThemePresets.select(null))
        assertEquals(ThemePresets.Preset.CARTEL, ThemePresets.select("removed-theme"))
    }

    @Test fun systemHighContrastOrsIntoThePersistedReduceTransparencyChoice() {
        ThemeAccessibility.applyDefaults(persistedReduceTransparency = false, systemHighContrast = true, systemAnimationsRemoved = false)
        assertTrue(ThemePresets.reduceTransparency)
        ThemeAccessibility.applyDefaults(persistedReduceTransparency = true, systemHighContrast = false, systemAnimationsRemoved = false)
        assertTrue(ThemePresets.reduceTransparency)
        ThemeAccessibility.applyDefaults(persistedReduceTransparency = false, systemHighContrast = false, systemAnimationsRemoved = false)
        assertTrue(!ThemePresets.reduceTransparency)
    }

    @Test fun systemAnimationsRemovedFlipsTheSharedMotionSwitchIndependently() {
        ThemeAccessibility.applyDefaults(persistedReduceTransparency = false, systemHighContrast = false, systemAnimationsRemoved = true)
        assertTrue(ThemePresets.systemAnimationsRemoved)
        assertTrue(!ThemePresets.reduceTransparency)
    }
}
