package com.carteltv.tv.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carteltv.tv.R
import com.carteltv.tv.TestBootApp
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.hero.HeroItem
import com.carteltv.tv.ui.hero.HeroSlide
import com.carteltv.tv.ui.hero.HeroSlider
import com.carteltv.tv.ui.home.HomeStage
import com.carteltv.tv.ui.home.Shelf
import com.carteltv.tv.ui.home.ShelfEntry
import com.carteltv.tv.ui.home.ShelfFace
import com.carteltv.tv.ui.nav.ScreenScaffold
import com.carteltv.tv.ui.nav.TopBar
import com.carteltv.tv.ui.theme.CartelTheme
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.github.takahirom.roborazzi.captureRoboImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    fun homeWithRealTimeOfDayArtAndPersistentShelves() {
        Nav.resetTo(Route.Home)
        val todayLabel = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date()).uppercase(Locale.getDefault())
        val continueWatching = Shelf(
            key = "continue",
            title = "Continue Watching",
            face = ShelfFace.CARD,
            entries = listOf(
                ShelfEntry.Card("one", "The Last Horizon", {}, subtitle = "S1:E4 · 18m left", progress = 0.62f),
                ShelfEntry.Card("two", "Midnight in Paris", {}, subtitle = "2011 · Romance", progress = 0.25f),
                ShelfEntry.Card("three", "A Quiet Place", {}, subtitle = "2018 · Thriller"),
                ShelfEntry.Card("four", "Dune: Part Two", {}),
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
        val today = Shelf(
            key = "glance",
            title = "Today",
            face = ShelfFace.INFO,
            entries = listOf(
                ShelfEntry.Info(
                    "glance:sports", "Sports", {}, "LIVE NOW: 3",
                    listOf("GB 13–3 KC · 2ND 9:33", "LAL 102–98 GSW · Q4 5:32", "+4 more live"),
                    ThemeSemantics.live,
                ),
                ShelfEntry.Info(
                    "glance:weather", "Weather", {}, "21°",
                    listOf("Sydney · 19:32 AEDT", "Partly cloudy", "Humidity 64%"),
                    ThemeSemantics.info,
                ),
                ShelfEntry.Info(
                    "glance:fx", "Exchange rates", {}, "1 AUD",
                    listOf("USD 0.65 +0.32%", "EUR 0.60 −0.14%", "Updated 2026-11-27"),
                    ThemeSemantics.info,
                ),
                ShelfEntry.Info(
                    "glance:aqi", "Air quality", {}, "AQI 42",
                    listOf("Good", "PM2.5 4.2 µg/m³"),
                    ThemeSemantics.success,
                ),
            ),
        )
        compose.setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize().background(ThemeSemantics.stage)) {
                    ScreenScaffold(bleedTop = true, bleedStart = true) {
                    HomeStage(
                        hero = { modifier, inset ->
                            HeroSlider(
                                slides = listOf(
                                    HeroSlide.Media(
                                        HeroItem(
                                            title = "Good morning",
                                            art = null,
                                            artRes = R.drawable.greeting_morning,
                                            description = "Ease into the day with live news, films and your watchlist.",
                                            badge = todayLabel,
                                            refId = "",
                                            url = "",
                                            opens = AppSection.SETUP,
                                        ),
                                    ),
                                ),
                                index = 0,
                                onIndexChange = {},
                                onOpen = {},
                                modifier = modifier,
                                contentInset = inset,
                            )
                        },
                        heroFocus = remember { FocusRequester() },
                        shelves = listOf(continueWatching, today, trending),
                        entryEnabled = true,
                    )
                    }
                    TopBar(
                        current = AppSection.HOME,
                        onGo = {},
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/netflix-home.png")
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onRoot().captureRoboImage("src/test/screenshots/netflix-home-widgets.png")
    }
}
