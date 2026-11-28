package com.carteltv.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastThemesTest {
    @After
    fun resetTheme() {
        ThemeAccessibility.reduceTransparency = false
        ThemeAccessibility.systemHighContrast = false
        ThemeAccessibility.systemAnimationsRemoved = false
        BroadcastThemes.select(BroadcastThemes.ESPN.id)
    }

    @Test
    fun presetsUseEachBroadcastersBrandPalette() {
        assertEquals(
            listOf("espn", "fox_sports", "sky_sports", "tnt_sports", "dazn", "bein_sports"),
            BroadcastThemes.presets.map { it.id },
        )
        fun assertPalette(theme: BroadcastTheme, canvas: Long, signature: Long, accent: Long, highlight: Long = 0xFFFFFFFF) {
            assertEquals(Color(canvas), theme.canvasPrimary)
            assertEquals(Color(signature), theme.signature)
            assertEquals(Color(accent), theme.accentPrimary)
            assertEquals(Color(highlight), theme.highlight)
        }
        assertPalette(BroadcastThemes.ESPN, 0xFF0A0B0D, 0xFF48494A, 0xFFDA020E)
        assertPalette(BroadcastThemes.FOX_SPORTS, 0xFF050B17, 0xFF081F57, 0xFF0059B5)
        assertPalette(BroadcastThemes.SKY_SPORTS, 0xFF000B24, 0xFF002675, 0xFFDF1A19)
        assertPalette(BroadcastThemes.TNT_SPORTS, 0xFF0A1218, 0xFF14232D, 0xFFFF28FF)
        assertPalette(BroadcastThemes.DAZN, 0xFF080E12, 0xFF242D33, 0xFFF8FC00)
        assertPalette(BroadcastThemes.BEIN_SPORTS, 0xFF050617, 0xFF5C2D91, 0xFFFFF967, 0xFFFA407F)
    }

    @Test
    fun accentTextAndOnAccentLabelsMeetWcagAaOnEveryPreset() {
        fun contrast(a: Color, b: Color): Float {
            val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
            return (hi + 0.05f) / (lo + 0.05f)
        }
        BroadcastThemes.presets.forEach { theme ->
            assertTrue("${theme.id} accentBright on canvas", contrast(theme.accentBright, theme.canvasPrimary) >= 4.5f)
            assertTrue("${theme.id} onAccent on accent", contrast(theme.onAccent, theme.accentPrimary) >= 4.5f)
            assertTrue("${theme.id} ink2 on canvas", contrast(theme.neutrals.ink2, theme.canvasPrimary) >= 7f)
            assertTrue("${theme.id} ink3 on canvas", contrast(theme.neutrals.ink3, theme.canvasPrimary) >= 4.5f)
        }
    }

    @Test
    fun neutralLadderCarriesTheSignatureHueAtTheHouseLightness() {
        val house = Color(0xFF808080).luminance()
        BroadcastThemes.presets.forEach { theme ->
            // Same lightness as the grey it replaces, within rounding, so contrast does not move between presets.
            assertEquals(house, theme.neutrals.ink3.luminance(), 0.01f)
        }
        // A blue brand gets blue-leaning greys; a grey brand stays grey.
        val sky = BroadcastThemes.SKY_SPORTS.neutrals.glassStrong
        assertTrue(sky.blue > sky.red)
        val espn = BroadcastThemes.ESPN.neutrals.ink3
        assertTrue(maxOf(espn.red, espn.green, espn.blue) - minOf(espn.red, espn.green, espn.blue) < 0.02f)
    }

    @Test
    fun focusRingIsNeutralWhileGlowUsesTheNetworkAccent() {
        BroadcastThemes.presets.forEach { theme ->
            assertEquals(Color(0xFFF7F8FA), theme.focusRingTint)
            assertEquals(theme.accentPrimary.copy(alpha = 0.38f), theme.focusGlowTint)
        }
    }

    @Test
    fun selectionUpdatesCanvasAndAccentImmediately() {
        val selected = BroadcastThemes.select(BroadcastThemes.TNT_SPORTS.id)
        assertEquals(BroadcastThemes.TNT_SPORTS, selected)
        assertEquals(selected.canvasPrimary, ThemeSemantics.stage)
        assertEquals(selected.accentPrimary, ThemeSemantics.accent)
        assertEquals(selected.signature, ThemeSemantics.signature)
        assertEquals(selected.highlight, ThemeSemantics.highlight)
        assertEquals(selected.focusRingTint, ThemeSemantics.focusRing)
    }

    @Test
    fun scrimsAndMediaGroundsEndInThePresetStage() {
        BroadcastThemes.presets.forEach { theme ->
            BroadcastThemes.select(theme.id)
            // A fade over artwork must land on the stage colour itself, not on a different black.
            assertEquals(theme.canvasPrimary, ThemeSemantics.scrimBlack)
            assertEquals(theme.canvasPrimary.copy(alpha = 0.45f), ThemeSemantics.scrimDeep)
            assertEquals(theme.canvasPrimary.copy(alpha = 0.70f), ThemeSemantics.scrimModal)
            assertTrue("${theme.id} mediaInk stays dark", ThemeSemantics.mediaInk.luminance() < 0.02f)
        }
    }

    @Test
    fun controlsShareOneStateLanguageOnEveryPreset() {
        BroadcastThemes.presets.forEach { theme ->
            BroadcastThemes.select(theme.id)
            // Focus outranks selection: the white key light with stage ink, whether or not the control is selected.
            listOf(false, true).forEach { selected ->
                assertEquals(ThemeSemantics.textEmphatic, ThemeSemantics.controlFill(focused = true, selected = selected))
                assertEquals(theme.canvasPrimary, ThemeSemantics.controlInk(focused = true, selected = selected))
            }
            assertEquals(theme.accentPrimary, ThemeSemantics.controlFill(focused = false, selected = true))
            assertEquals(theme.onAccent, ThemeSemantics.controlInk(focused = false, selected = true))
            assertEquals(ThemeSemantics.surfaceRest, ThemeSemantics.controlFill(focused = false))
            assertEquals(ThemeSemantics.textEmphatic, ThemeSemantics.controlInk(focused = false))
        }
    }

    @Test
    fun missingOrUnknownIdsFallBackToEspn() {
        assertEquals(BroadcastThemes.ESPN, BroadcastThemes.select(null))
        assertEquals(BroadcastThemes.ESPN, BroadcastThemes.select("removed-theme"))
    }

    @Test
    fun accessibilityOverridesRemainIndependentOfThemeSelection() {
        ThemeAccessibility.applyDefaults(persistedReduceTransparency = false, systemHighContrast = true, systemAnimationsRemoved = false)
        assertTrue(ThemeAccessibility.reduceTransparency)
        assertTrue(ThemeAccessibility.systemHighContrast)
        ThemeAccessibility.applyDefaults(persistedReduceTransparency = false, systemHighContrast = false, systemAnimationsRemoved = true)
        assertTrue(!ThemeAccessibility.reduceTransparency)
        assertTrue(ThemeAccessibility.systemAnimationsRemoved)
    }
}