package com.carteltv.tv.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carteltv.tv.TestBootApp
import com.carteltv.tv.data.espn.EspnScoreboardClient
import com.carteltv.tv.ui.sports.FeaturedGame
import com.carteltv.tv.ui.sports.GameCard
import com.carteltv.tv.ui.theme.CartelTheme
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reference render for the Sports board pieces, at TV size (960 x 540 dp). Record with
 * `-Proborazzi.test.record=true`; the PNG lands in src/test/screenshots.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestBootApp::class, qualifiers = "w960dp-h540dp-xhdpi")
class SportsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun live(
        a: String,
        b: String,
        abbrA: String,
        abbrB: String,
        nameA: String,
        nameB: String,
        recA: String,
        recB: String,
        colorA: String,
        colorB: String,
        status: String,
        net: String? = null,
        venue: String? = null,
        loc: String? = null,
        odds: String? = null,
    ) = EspnScoreboardClient.Score(
        name = "$nameB at $nameA",
        detail = status,
        state = "in",
        logoA = null,
        abbrA = abbrA,
        scoreA = a,
        logoB = null,
        abbrB = abbrB,
        scoreB = b,
        nameA = nameA,
        nameB = nameB,
        recordA = recA,
        recordB = recB,
        colorA = colorA,
        colorB = colorB,
        shortDetail = status,
        clock = "9:33",
        period = 2,
        network = net,
        venue = venue,
        location = loc,
        odds = odds,
    )

    private val lakers =
        live(
            "102",
            "98",
            "LAL",
            "GSW",
            "Lakers",
            "Warriors",
            "10-3",
            "9-4",
            "552583",
            "1D428A",
            "Q4 5:32",
            net = "ESPN",
            venue = "Crypto.com Arena",
            loc = "Los Angeles, CA",
            odds = "LAL -3.5",
        )

    private val upcoming =
        EspnScoreboardClient.Score(
            name = "Chiefs at Bills",
            detail = "",
            state = "pre",
            logoA = null,
            abbrA = "BUF",
            scoreA = null,
            logoB = null,
            abbrB = "KC",
            scoreB = null,
            nameA = "Bills",
            nameB = "Chiefs",
            recordA = "8-3",
            recordB = "9-2",
            startMs = System.currentTimeMillis() + 2 * 3600_000L,
            colorA = "00338D",
            colorB = "E31837",
            network = "CBS",
            venue = "Highmark Stadium",
            location = "Orchard Park, NY",
        )

    private val finalGame =
        EspnScoreboardClient.Score(
            name = "Celtics at Heat",
            detail = "Final",
            state = "post",
            logoA = null,
            abbrA = "MIA",
            scoreA = "88",
            logoB = null,
            abbrB = "BOS",
            scoreB = "104",
            nameA = "Heat",
            nameB = "Celtics",
            recordA = "6-5",
            recordB = "11-2",
            colorA = "98002E",
            colorB = "007A33",
            shortDetail = "Final",
            network = "NBA TV",
        )

    @Test
    fun featured_and_cards() {
        compose.setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize().background(ThemeSemantics.stage)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        FeaturedGame(
                            league = "NBA",
                            g = lakers,
                            followed = true,
                            shielded = false,
                            rights = listOf("ESPN"),
                            modifier = Modifier.fillMaxWidth(),
                            onToggle = {},
                        )
                        GameCard(
                            league = "NBA",
                            g = lakers,
                            followed = false,
                            shielded = false,
                            rights = listOf("ESPN"),
                            modifier = Modifier.fillMaxWidth(),
                            onToggle = {},
                        )
                        GameCard(
                            league = "NFL",
                            g = upcoming,
                            followed = false,
                            shielded = false,
                            rights = listOf("CBS"),
                            modifier = Modifier.fillMaxWidth(),
                            onToggle = {},
                        )
                        GameCard(
                            league = "NBA",
                            g = finalGame,
                            followed = false,
                            shielded = false,
                            rights = listOf("NBA TV"),
                            modifier = Modifier.fillMaxWidth(),
                            onToggle = {},
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/sports-redesign.png")
    }
}
