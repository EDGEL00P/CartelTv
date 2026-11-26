package com.carteltv.tv.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.theme.CartelTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot

/**
 * Reference images for the shared components, rendered on the JVM at TV size
 * (1920 x 1080, as Plasma's screens are). Record with
 * `./gradlew recordRoborazziDebug`; CI verifies with `-Proborazzi.test.verify=true`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = com.carteltv.tv.TestBootApp::class, qualifiers = "w1920dp-h1080dp")
class ComponentsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chips_at_rest_selected_and_with_the_focus_dot() {
        compose.setContent {
            CartelTheme {
                Column(Modifier.padding(48.dp)) {
                    Chip("Live TV", selected = true, onClick = {})
                    Chip("Movies", selected = false, onClick = {})
                    Chip("Sports", selected = false, dot = androidx.compose.ui.graphics.Color(0xFFE53935), onClick = {})
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/01-chips.png")
    }

    @Test
    fun a_section_title_with_its_note() {
        compose.setContent {
            CartelTheme {
                SectionTitle("On Air", note = "3 channels live")
            }
        }
        compose.onRoot().captureRoboImage("02-section-title.png")
    }

    @Test
    fun the_empty_state_for_a_group_without_channels() {
        compose.setContent {
            CartelTheme {
                EmptyState("No channels in this group", hint = "The provider returned nothing for this selection.")
            }
        }
        compose.onRoot().captureRoboImage("03-empty-state.png")
    }
}
