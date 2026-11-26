package com.carteltv.tv.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import com.carteltv.tv.TestBootApp
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.home.HomeStage
import com.carteltv.tv.ui.home.Shelf
import com.carteltv.tv.ui.home.ShelfEntry
import com.carteltv.tv.ui.home.ShelfFace
import com.carteltv.tv.ui.nav.TopBar
import com.carteltv.tv.ui.theme.CartelTheme
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestBootApp::class, qualifiers = "w960dp-h540dp-xhdpi")
class NetflixHomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cinematic_home_with_top_navigation_and_persistent_shelves() {
        Nav.resetTo(Route.Home)
        val continueWatching = Shelf(
            key = "continue",
            title = "Continue Watching",
            face = ShelfFace.CARD,
            entries = listOf(
                ShelfEntry.Card("one", "The Last Horizon", {}, subtitle = "S1:E4 · 18m left", progress = 0.62f),
                ShelfEntry.Card("two", "Midnight in Paris", {}, subtitle = "2011 · Romance", progress = 0.25f),
                ShelfEntry.Card("three", "A Quiet Place", {}, subtitle = "2018 · Thriller"),
                ShelfEntry.Card("four", "Dune: Part Two", {}, subtitle = "2024 · Adventure"),
            ),
        )
        val trending = Shelf(
            key = "trending",
            title = "Trending Now",
            face = ShelfFace.CATALOG,
            entries = listOf(
                ShelfEntry.Card("five", "Dark Matter", {}, subtitle = "#1 in TV Shows"),
                ShelfEntry.Card("six", "The Crown", {}, subtitle = "Emmy Award Winner"),
                ShelfEntry.Card("seven", "Arcane", {}, subtitle = "Highly Rewatched"),
            ),
        )
        // The Today row: the glance widgets as the live home shows them, with each card's own semantic accent.
        val today = Shelf(
            key = "glance",
            title = "Today",
            face = ShelfFace.INFO,
            entries = listOf(
                ShelfEntry.Info(
                    "glance:sports", "Sports", {}, "LIVE NOW: 3",
                    listOf("GB 13\u20133 KC \u00b7 2ND 9:33", "LAL 102\u201398 GSW \u00b7 Q4 5:32", "+4 more live"),
                    ThemeSemantics.live,
                ),
                ShelfEntry.Info(
                    "glance:weather", "Weather", {}, "21\u00b0",
                    listOf("Sydney \u00b7 19:32 AEDT", "Partly cloudy", "Humidity 64%"),
                    ThemeSemantics.info,
                ),
                ShelfEntry.Info(
                    "glance:fx", "Exchange rates", {}, "1 AUD",
                    listOf("USD 0.65 +0.32%", "EUR 0.60 \u22120.14%", "Updated 2026-11-27"),
                    ThemeSemantics.info,
                ),
                ShelfEntry.Info(
                    "glance:aqi", "Air quality", {}, "AQI 42",
                    listOf("Good", "PM2.5 4.2 \u00b5g/m\u00b3"),
                    ThemeSemantics.success,
                ),
            ),
        )
        compose.setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize().background(ThemeSemantics.stage)) {
                    HomeStage(
                        hero = { modifier, inset ->
                            Box(
                                modifier.background(
                                    Brush.horizontalGradient(
                                        0f to Color(0xFF141414),
                                        0.55f to Color(0xFF352027),
                                        1f to Color(0xFF73101C),
                                    ),
                                ),
                            ) {
                                Column(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = inset + ThemeSpec.Space.xl, bottom = 40.dp),
                                ) {
                                    Text("FEATURED FILM", color = ThemeSemantics.accentBright, fontSize = ThemeSpec.Type.label)
                                    Text(
                                        "The Last Horizon",
                                        color = ThemeSemantics.onMedia,
                                        fontSize = ThemeSpec.Type.display,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "2026 · Sci-Fi · 2h 08m",
                                        color = ThemeSemantics.onMediaMuted,
                                        fontSize = ThemeSpec.Type.meta,
                                    )
                                }
                            }
                        },
                        heroFocus = remember { FocusRequester() },
                        shelves = listOf(continueWatching, trending),
                        entryEnabled = false,
                    )
                    TopBar(
                        current = AppSection.HOME,
                        onGo = {},
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/netflix-home.png")
    }
}
