package com.carteltv.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carteltv.tv.ui.theme.CartelTheme
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reference images for the player deck, its focus state, the live chips and the host's cards. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = com.carteltv.tv.TestBootApp::class, qualifiers = "w1920dp-h1080dp")
class PlayerDeckScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun Deck() {
        Column(
            Modifier.fillMaxWidth().playerGlass(deckShape, heavy = true)
                .padding(horizontal = ThemeSpec.Space.xl, vertical = ThemeSpec.Space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CHANNEL 12", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim)
                    Text(
                        "Severance",
                        fontSize = ThemeSpec.Type.cardTitle, fontWeight = FontWeight.Bold,
                        fontFamily = ThemeSpec.Font.broadcast, color = PlayerChrome.ink, maxLines = 1,
                    )
                }
                Text("ends 22:15", fontSize = ThemeSpec.Type.meta, color = PlayerChrome.inkMuted)
            }
            PlayerScrubber(
                position = 1_800_000L,
                duration = 6_000_000L,
                buffered = 2_400_000L,
                chapters = listOf(600_000L, 1_500_000L, 3_300_000L),
                onSeek = {},
                onTogglePlay = {},
            )
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(top = ThemeSpec.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) {
                Box(
                    Modifier.size(40.dp).background(ThemeSemantics.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { PlayerGlyphIcon(PlayerGlyph.PLAY, PlayerChrome.ink, 22.dp) }
                PlayerBarIconButton(PlayerGlyph.BACK, "10s", onClick = {})
                PlayerBarIconButton(PlayerGlyph.FORWARD, "10s", onClick = {})
                PlayerBarIconButton(PlayerGlyph.SKIP_NEXT, "Next", onClick = {})
                PlayerBarIconButton(PlayerGlyph.VOICE, "Audio", onClick = {})
                PlayerBarIconButton(PlayerGlyph.SUBS, "Subtitles", onClick = {})
                PlayerBarIconButton(PlayerGlyph.MORE, "Settings", active = true, onClick = {})
                PlayerBarIconButton(PlayerGlyph.INFO, "Info", onClick = {})
                Spacer(Modifier.weight(1f))
                PlayerBarIconButton(PlayerGlyph.HOME, "Close", onClick = {})
            }
        }
    }

    @Test
    fun the_deck_with_its_settings_panel() {
        compose.setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF202024)).padding(48.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
                        Deck()
                        Column(
                            Modifier.fillMaxWidth().playerGlass(deckShape, heavy = true)
                                .padding(horizontal = ThemeSpec.Space.xl, vertical = ThemeSpec.Space.lg),
                        ) {
                            PlayerSettingsTiles(
                                onShowTracks = {},
                                onShowDataPool = {},
                                onCycleAspect = {},
                                onCycleSpeed = {},
                                onCycleBoost = {},
                                onCycleSubScale = {},
                                onOpenSidePanel = {},
                                onOpenSchedule = {},
                                onSourceSwitch = {},
                                onMultiview = {},
                                onSearch = {},
                                aspectName = "Fit",
                                currentSpeed = 1f,
                                boostLevel = 0,
                                subScale = 100,
                                isLive = true,
                            )
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/04-player-deck.png")
    }

    @Test
    fun a_focused_control() {
        compose.setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF202024)).padding(48.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                        PlayerBarIconButton(PlayerGlyph.BACK, "10s", onClick = {})
                        PlayerBarIconButton(PlayerGlyph.SUBS, "Subtitles", focusedOverride = true, onClick = {})
                        PlayerBarIconButton(PlayerGlyph.INFO, "Info", onClick = {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/05-player-focus.png")
    }

    @Test
    fun the_live_header_and_quality_chips() {
        compose.setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
                    Column(Modifier.padding(48.dp)) {
                        PlayerTopStatus(
                            meta = PlayerMeta(isLive = true, channelName = "Cartel Sports HD", channelNumber = 12, title = "Live"),
                            info = mapOf("Video class" to "UHD 4K", "HDR class" to "Dolby Vision", "Audio layout" to "5.1"),
                        )
                        Row(
                            Modifier.padding(top = ThemeSpec.Space.lg).playerGlass(deckShape, heavy = true)
                                .padding(horizontal = ThemeSpec.Space.xl, vertical = ThemeSpec.Space.lg),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
                        ) {
                            PlayerStatusPill("LIVE", PlayerChrome.ink, live = true)
                            Text("CHANNEL 12", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkDim)
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/06-player-live.png")
    }

    @Test
    fun the_up_next_card() {
        compose.setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101014)), contentAlignment = Alignment.Center) {
                    UpNextCard(
                        title = "Who Is Alive?",
                        subtitle = "Severance \u00b7 S2 E3",
                        overview = "Mark and the team search for a familiar face while the halls shift around them.",
                        secondsLeft = 12,
                        onPlayNow = {},
                        onCancel = {},
                        onSetReminder = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/07-player-up-next.png")
    }

    @Test
    fun the_error_card() {
        compose.setContent {
            CartelTheme {
                Box(Modifier.fillMaxSize()) {
                    PlayerErrorCard(
                        message = "The stream stopped. Wait a moment, then retry.",
                        detail = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                        onRetry = {},
                        onClose = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/08-player-error.png")
    }
}
