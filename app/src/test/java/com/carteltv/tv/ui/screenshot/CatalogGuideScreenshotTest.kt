package com.carteltv.tv.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carteltv.tv.R
import com.carteltv.tv.TestBootApp
import com.carteltv.tv.data.xtream.GuideModel
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.catalog.CatalogHero
import com.carteltv.tv.ui.catalog.HeroPick
import com.carteltv.tv.ui.catalog.PosterRail
import com.carteltv.tv.ui.components.GridPoster
import com.carteltv.tv.ui.components.PosterTile
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.live.GuideProgramCell
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.theme.CartelTheme
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestBootApp::class, qualifiers = "w960dp-h540dp-xhdpi")
class CatalogGuideScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun five_landscape_catalog_cards_with_title_plates() {
        val titles =
            listOf(
                "The Last Horizon" to "2026 · Sci-Fi",
                "Midnight in Paris" to "2011 · Romance",
                "A Quiet Place" to "2018 · Thriller",
                "Dune: Part Two" to "2024 · Adventure",
                "The Grand Budapest Hotel" to "2014 · Comedy",
            )
        compose.setContent {
            CartelTheme {
                Column(
                    Modifier.fillMaxSize().background(ThemeSemantics.stage).padding(48.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    SectionTitle("Popular Movies", note = "5 titles")
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        titles.forEachIndexed { index, (title, meta) ->
                            Box(Modifier.weight(1f)) {
                                GridPoster(
                                    imageUrl = null,
                                    title = title,
                                    subtitle = meta,
                                    badge = if (index == 0) "★ 8.1" else null,
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/movies-grid.png")
    }

    @Test
    fun masthead_uses_actions_instead_of_a_status_hud() {
        Nav.resetTo(Route.Home)
        compose.setContent {
            CartelTheme {
                ScreenPage("Movies", icon = R.drawable.ph_film_strip) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/masthead-actions.png")
    }

    @Test
    fun current_and_upcoming_programme_cards() {
        val start = 1_800_000_000_000L
        val now = start + 30 * 60_000L
        val current = GuideModel.Slot("The Late Report", "Live news and current affairs.", start, start + 60 * 60_000L)
        val upcoming = GuideModel.Slot("Horizon", "A documentary about the natural world.", start + 60 * 60_000L, start + 90 * 60_000L)
        compose.setContent {
            CartelTheme {
                Column(
                    Modifier.fillMaxSize().background(ThemeSemantics.stage).padding(start = 48.dp, end = 48.dp, top = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(68.dp)) {
                        GuideProgramCell(
                            slot = current,
                            phase = GuideModel.Phase.LIVE,
                            now = now,
                            onFocused = {},
                            onTune = {},
                            onDetails = {},
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(68.dp)) {
                        GuideProgramCell(
                            slot = upcoming,
                            phase = GuideModel.Phase.FUTURE,
                            now = now,
                            onFocused = {},
                            onTune = {},
                            onDetails = {},
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/guide-programs.png")
    }

    @Test
    fun on_demand_hero_billboard() {
        compose.setContent {
            CartelTheme {
                Column(
                    Modifier.fillMaxSize().background(ThemeSemantics.stage).padding(start = 48.dp, end = 48.dp, top = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CatalogHero(
                        HeroPick(
                            title = "Dune: Part Two",
                            art = null,
                            meta = "2024 · ★ 8.5",
                            cta = "More info",
                            onOpen = {},
                        ),
                    )
                    SectionTitle("Popular Movies", note = "5 titles")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("The Last Horizon", "Midnight in Paris", "A Quiet Place", "Dune: Part Two", "The Grand Budapest Hotel")
                            .forEach { t ->
                                Box(Modifier.width(160.dp)) { PosterTile(imageUrl = null, title = t, meta = "2024", onClick = {}) }
                            }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/on-demand-hero.png")
    }

    @Test
    fun catalog_family_billboard_and_rails() {
        compose.setContent {
            CartelTheme {
                Column(
                    Modifier.fillMaxSize().background(ThemeSemantics.stage).padding(start = 48.dp, end = 48.dp, top = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CatalogHero(
                        HeroPick(
                            title = "Dune: Part Two",
                            art = null,
                            meta = "2024 · ★ 8.5",
                            cta = "Resume",
                            onOpen = {},
                        ),
                    )
                    PosterRail("Continue watching", count = 5) {
                        items(
                            listOf("The Last Horizon", "Midnight in Paris", "A Quiet Place", "Dune: Part Two", "Arrival"),
                            key = { it },
                        ) { t ->
                            PosterTile(imageUrl = null, title = t, meta = "2024", onClick = {})
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/catalog-family.png")
    }
}
