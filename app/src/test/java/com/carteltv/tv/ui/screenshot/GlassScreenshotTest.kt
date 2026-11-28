package com.carteltv.tv.ui.screenshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import com.carteltv.tv.TestBootApp
import com.carteltv.tv.ui.nav.ScreenScaffold
import com.carteltv.tv.ui.theme.CartelTheme
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.glassBackdropSource
import com.carteltv.tv.ui.theme.glassPane
import com.carteltv.tv.ui.theme.glassSurface
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Glass proof: panes over a loud, detailed backdrop. If the fills ever creep back to
 * opaque charcoal, this capture turns into grey boxes on a rainbow and the regression
 * is visible at a glance — the unit tests pin the alphas, this pins the look.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestBootApp::class, qualifiers = "w960dp-h540dp-xhdpi")
class GlassScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun glassOverColourfulBackdrop() {
        compose.setContent {
            CartelTheme {
                ScreenScaffold(bleedTop = true, bleedStart = true) {
                    Box(Modifier.fillMaxSize()) {
                        // Registered as the backdrop source, like a screen's full-bleed artwork:
                        // the floating pane frosts this, the cards ghost it through their tint.
                        Box(Modifier.fillMaxSize().glassBackdropSource()) { ColourField() }
                        Column(
                            Modifier.fillMaxSize().padding(48.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            GlassCard(focused = false, label = "Resting glass — backdrop ghosts through")
                            GlassCard(focused = true, label = "Focused glass — rim, glow, preset tint")
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(
                                        120.dp,
                                    ).glassPane(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                                    .padding(16.dp),
                            ) {
                                Text(
                                    "Floating pane — dialog / player material",
                                    color = ThemeSemantics.textPrimary,
                                    fontSize = ThemeSpec.Type.body,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/glass-surfaces.png")
    }

    @Composable
    private fun GlassCard(
        focused: Boolean,
        label: String,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .glassSurface(focused, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .padding(16.dp),
        ) {
            Text(label, color = ThemeSemantics.textPrimary, fontSize = ThemeSpec.Type.body)
        }
    }

    @Composable
    private fun ColourField() {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFF7C3AED), Color(0xFFE50914), Color(0xFFFF6B35), Color(0xFF38BDF8), Color(0xFF5EEAD4)),
                ),
            )
            val colours = listOf(Color.White, Color.Yellow, Color.Cyan, Color.Magenta, Color.Green, Color.White)
            colours.forEachIndexed { i, colour ->
                drawCircle(
                    colour.copy(alpha = 0.55f),
                    radius = 90f + i * 22f,
                    center = Offset(size.width * (0.12f + i * 0.16f), size.height * if (i % 2 == 0) 0.28f else 0.72f),
                )
            }
        }
    }
}
