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
import com.carteltv.tv.data.espn.EspnScoreboardClient.Score
import com.carteltv.tv.data.finance.ExchangeRates
import com.carteltv.tv.data.finance.Rate
import com.carteltv.tv.data.weather.AirQuality
import com.carteltv.tv.data.weather.Pollutant
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.hero.HeroItem
import com.carteltv.tv.ui.hero.HeroSlide
import com.carteltv.tv.ui.hero.HeroSlider
import com.carteltv.tv.ui.home.Glance
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
class HomeScreenshotTest {
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
                ShelfEntry.Info("glance:sports", "Sports", {}, Glance.Live(liveGames, "LIVE NOW: 5", 0L)),
                ShelfEntry.Info(
                    "glance:weather", "Weather", {},
                    Glance.Weather(
                        tempC = 21.0, code = 0, isDay = false, place = "Sydney", localTime = "21:32",
                        highC = 26.0, lowC = 17.0, detail = "Feels like 20°",
                    ),
                ),
                ShelfEntry.Info(
                    "glance:fx", "Exchange rates", {},
                    Glance.Fx(
                        ExchangeRates(
                            "AUD", "2026-11-27",
                            listOf(Rate("USD", 0.6521, 0.6500), Rate("EUR", 0.6012, 0.6020), Rate("JPY", 98.41, 98.10)),
                        ),
                    ),
                ),
                ShelfEntry.Info("glance:aqi", "Air quality", {}, Glance.Air(
                        AirQuality(
                            112, 61, 39.6, 44.0, "2026-11-28T19:00",
                            mapOf(Pollutant.PM25 to 112, Pollutant.PM10 to 41, Pollutant.OZONE to 37),
                        ),
                    ),),
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
        compose.onRoot().captureRoboImage("src/test/screenshots/home.png")
        compose.onRoot().performTouchInput { swipeUp() }
        compose.onRoot().captureRoboImage("src/test/screenshots/home-widgets.png")
    }
}

/** Five live games, so the sports widget shows two lines and "+3 more live". Logos stay null: the test has no network. */
private val liveGames =
    listOf(
        Score("nfl", "", "in", null, "KC", "3", null, "GB", "13", period = 2, clock = "9:33", shortDetail = "2nd 9:33", colorA = "E31837", colorB = "203731"),
        Score("nba", "", "in", null, "GSW", "98", null, "LAL", "102", period = 4, clock = "5:32", shortDetail = "Q4 5:32", colorA = "1D428A", colorB = "552583"),
        Score("nhl", "", "in", null, "BOS", "2", null, "TOR", "2", period = 3, clock = "4:10"),
        Score("epl", "", "in", null, "ARS", "1", null, "CHE", "0", period = 2, clock = "67'"),
        Score("mlb", "", "in", null, "NYY", "4", null, "LAD", "5", period = 7),
    )
