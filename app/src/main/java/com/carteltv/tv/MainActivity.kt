package com.carteltv.tv

import android.app.SearchManager
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.preferences.Profiles
import com.carteltv.tv.data.watch.WatchNextRepository
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.system.PackageChangedReceiver
import com.carteltv.tv.nav.AppSection
import com.carteltv.tv.nav.HomeReset
import com.carteltv.tv.ui.settings.KidsVeil
import com.carteltv.tv.ui.settings.LoginScreen
import com.carteltv.tv.ui.settings.MoreTimeApproval
import com.carteltv.tv.ui.components.DpadKeys
import com.carteltv.tv.ui.nav.NavHostLayer
import com.carteltv.tv.ui.settings.PendingFaceGate
import com.carteltv.tv.ui.settings.PinGate
import com.carteltv.tv.ui.settings.ProfilePickerOverlay
import com.carteltv.tv.ui.apps.AllAppsOverlay


import com.carteltv.tv.ui.components.OverlayCoordinator
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.theme.CartelTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import com.carteltv.tv.ui.nav.ShellIdleTimer
import com.carteltv.tv.nav.SectionNav
import com.carteltv.tv.ui.nav.ShellChrome
import com.carteltv.tv.ui.nav.TopBar
import com.carteltv.tv.ui.settings.CompanionOverlay
import com.carteltv.tv.ui.settings.SettingsDeepLink

/**
 * Single-shell TV launcher host. Every top-level section renders as a [Route]
 * inside this window with unified top-bar navigation, profile enforcement, and
 * deterministic key handling.
 */
@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {

    private var showLogin by mutableStateOf(false)
    private var tick by mutableIntStateOf(0)
    private val pkgReceiver = PackageChangedReceiver()
    private var pkgReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.carteltv.tv.companion.PhoneKeys.sink = { ev -> runOnUiThread { dispatchKeyEvent(ev) } }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                pkgReceiver,
                PackageChangedReceiver.intentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            pkgReceiverRegistered = true
        }
        if (savedInstanceState == null && AppPreferences.askProfileOnBoot && Profiles.state.value.size >= 2) {
            Profiles.pickerShown = true
        }
        // Android 13+ gates every notification (episode digest, recordings, score alerts) behind the
        // runtime POST_NOTIFICATIONS grant, so stock Google TV / Fire OS boxes show none without this
        // one-time ask; the install-granted helper only covers sideloads via adb.
        if (Build.VERSION.SDK_INT >= 33 && !AppPreferences.notifPromptDone && AppPreferences.providers().isNotEmpty()) {
            AppPreferences.notifPromptDone = true
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF,
            )
        }
        if (savedInstanceState == null || Nav.stack.size <= 1) {
            if (!handleWatchLink(intent)) intent.getStringExtra("goto")?.let { pushRoute(it) }
        }
        handleAssistIntent(intent)

        setContent {
            CartelTheme {
                // Back-button lock: at the root Home surface, never exit back to the stock launcher.
                BackHandler(enabled = AppPreferences.overrideBackButton && Nav.stack.size <= 1 && !showLogin) {
                    HomeReset.tick++
                }
                BackHandler(enabled = showLogin) { showLogin = false }
                BackHandler(enabled = Nav.stack.size > 1 && !OverlayCoordinator.locked) {
                    Nav.pop()
                }

                // Marks time-to-fully-drawn for startup tracing once the first frames have landed.
                LaunchedEffect(Unit) {
                    repeat(3) { withFrameNanos { } }
                    reportFullyDrawn()
                }

                // Re-evaluate kids bedtime / daily allowance once per minute.
                var clockMinute by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(60_000)
                        clockMinute++
                    }
                }
                val activeProfile = Profiles.active()
                val usageSnapshot = Profiles.usage
                val kidsBlock = remember(activeProfile, usageSnapshot, clockMinute) {
                    Profiles.evaluate(activeProfile, Calendar.getInstance())
                }

                val locked = kidsBlock != null || Profiles.pickerShown || MoreTimeApproval.ask
                LaunchedEffect(locked) {
                    OverlayCoordinator.locked = locked
                    if (locked) {
                        OverlayCoordinator.closeTransient()
                    }
                }

                val focusManager = LocalFocusManager.current
                // True while anything under the shell holds focus. A closing overlay usually hands focus straight back
                // through the restorer below; the search here is only for when it left nothing focused.
                var shellFocused by remember { mutableStateOf(false) }
                LaunchedEffect(OverlayCoordinator.restoreTick) {
                    if (OverlayCoordinator.restoreTick == 0) return@LaunchedEffect
                    repeat(3) { withFrameNanos { } }
                    if (!OverlayCoordinator.anyOpen() && !shellFocused) {
                        if (ShellChrome.lastContentFocus?.awaitFocus() == true) return@LaunchedEffect
                        // Identity restoration may have attached and focused the remembered item while we waited.
                        if (!OverlayCoordinator.anyOpen() && !shellFocused) {
                            listOf(FocusDirection.Enter, FocusDirection.Next, FocusDirection.Down)
                                .firstOrNull { focusManager.moveFocus(it) }
                        }
                    }
                }

                Box(Modifier.fillMaxSize().onFocusChanged { shellFocused = it.hasFocus }) {
                    // focusRestorer: when a dialog or overlay closes, focus returns to the item that opened it.
                    // With nothing to restore the restorer would take the first stop in the tree;
                    // Home's banner is where the shell wants focus.
                    Box(Modifier.fillMaxSize().focusGroup().focusRestorer { ShellChrome.lastContentFocus ?: FocusRequester.Default }) {
                        NavHostLayer()
                        val railTab = SectionNav.tabOf(Nav.current)
                        if (railTab != null && !ShellChrome.barHidden) {
                            TopBar(
                                current = railTab,
                                onGo = SectionNav::go,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                            )
                        }
                    }
                    AllAppsOverlay()
                    com.carteltv.tv.ui.settings.CompanionOverlay()
                    com.carteltv.tv.ui.components.StatusMessageHost()



                    if (kidsBlock != null) {
                        val parentPinArmed = AppPreferences.settingsPinOn && AppPreferences.settingsPinIsSet()
                        KidsVeil(
                            block = kidsBlock,
                            parentPinArmed = parentPinArmed,
                            onSwitchFaces = { Profiles.pickerShown = true },
                        )
                    }
                    if (MoreTimeApproval.ask) {
                        PinGate(
                            title = "Parent PIN",
                            onOk = { code ->
                                if (AppPreferences.settingsPinVerify(code)) {
                                    Profiles.grantMore(15)
                                    MoreTimeApproval.ask = false
                                    true
                                } else {
                                    false
                                }
                            },
                            onCancel = { MoreTimeApproval.ask = false },
                        )
                    }
                    if (Profiles.pickerShown && Profiles.state.value.isNotEmpty()) {
                        ProfilePickerOverlay(
                            onClose = if (kidsBlock == null) ({ Profiles.pickerShown = false }) else null,
                        )
                    }
                    PendingFaceGate()
                    com.carteltv.tv.ui.components.TvDialogHost()
                    com.carteltv.tv.ui.components.PhoneTypePill(
                        Modifier
                            .align(androidx.compose.ui.Alignment.BottomEnd)
                            .padding(end = com.carteltv.tv.ui.theme.ThemeSpec.Safe.horizontal, bottom = com.carteltv.tv.ui.theme.ThemeSpec.Safe.vertical + com.carteltv.tv.ui.theme.ThemeSpec.Space.lg),
                    )
                }

                if (showLogin) {
                    LoginScreen(onLoggedIn = { showLogin = false; tick++ })
                }
                @Suppress("UNUSED_EXPRESSION") tick
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            com.carteltv.tv.engine.stability.BackgroundWorkScheduler.onNavigation()
            com.carteltv.tv.ui.nav.ShellIdleTimer.poke()
        }
        // Remote mic / assistant / search keys start the on-device voice command.
        if (event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || event.keyCode == KeyEvent.KEYCODE_ASSIST || event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !OverlayCoordinator.locked) {
                com.carteltv.tv.voice.VoiceEngine.toggle(this)
            }
            return true
        }
        if (DpadKeys.isStaleRepeat(event)) return true
        // Menus use selection-driven keys; input and multi-choice dialogs keep Compose's native traversal.
        val dialog = com.carteltv.tv.ui.components.TvDialogs.current
        if (dialog != null) {
            if (event.action == KeyEvent.ACTION_DOWN && com.carteltv.tv.ui.components.TvDialogs.dispatchKey(event.keyCode)) return true
            if (!DpadKeys.usesNativeDialogTraversal(dialog) && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        }
        // Consume MENU before PhoneWindow turns it into the legacy options panel.
        // Player and focused-item handlers own it first; an unhandled press stays in context.
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !OverlayCoordinator.locked) {
                if (!com.carteltv.tv.ui.player.PlayerRemote.toggleActiveDeck()) {
                    com.carteltv.tv.ui.nav.MenuKeyHub.tryHandle()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // Invalidate the Watch Next cache whenever returning from a launched app.
        WatchNextRepository.invalidate()
        com.carteltv.tv.data.provider.ChannelRepository.start(this)
        // On Android 15+ the boot path cannot start the game monitor, but a visible activity can.
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            runCatching { com.carteltv.tv.alert.LiveScoreMonitor.startIfFollowing(this) }
        }
    }

    override fun onStop() {
        // An open mic and recognizer must not outlive the screen.
        if (com.carteltv.tv.voice.VoicePipeline.phase == com.carteltv.tv.voice.VoicePhase.LISTENING) {
            com.carteltv.tv.voice.VoiceEngine.stop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (pkgReceiverRegistered) {
            runCatching { unregisterReceiver(pkgReceiver) }
            pkgReceiverRegistered = false
        }
        com.carteltv.tv.companion.PhoneKeys.sink = null
        super.onDestroy()
    }

    private fun pushRoute(name: String) {
        val r = when (name) {
            "live" -> Route.LiveTv
            "movies" -> Route.Movies
            "series" -> Route.SeriesBrowse
            "scores", "sports" -> Route.Scores
            "weather" -> Route.Weather
            "radar" -> { com.carteltv.tv.ui.weather.WeatherDeepLink.radar = true; Route.Weather }
            "guide" -> Route.Guide
            "addons" -> { com.carteltv.tv.ui.settings.SettingsDeepLink.addons = true; Route.Settings }
            "rec", "recordings" -> { com.carteltv.tv.ui.settings.SettingsDeepLink.recordings = true; Route.Settings }
            "youtube" -> Route.YouTube
            "music", "youtubemusic" -> Route.YouTubeMusic
            "search" -> Route.Search
            "settings" -> Route.Settings
            "apps" -> Route.Apps
            "recents" -> Route.Recents
            else -> return
        }
        Nav.resetTo(Route.Home)
        Nav.push(r)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val message = when (requestCode) {
            com.carteltv.tv.voice.AssistantRole.REQUEST_CODE ->
                com.carteltv.tv.voice.AssistantRole.resultMessage(this, resultCode)
            com.carteltv.tv.system.HomeRole.REQUEST_CODE ->
                com.carteltv.tv.system.HomeRole.resultMessage(this, resultCode)
            else -> return
        }
        com.carteltv.tv.ui.components.TransientStatus.show(message)
        tick++
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        com.carteltv.tv.voice.VoiceEngine.onPermissionResult(this, requestCode, grantResults)
    }

    /** Cartel Assistant entry points: the mic key (ASSIST), voice dial, media search and carteltv://say?q= links. */
    private fun handleAssistIntent(i: Intent?) {
        val action = i?.action
        // Cartel is the default assist app: the remote mic button opens our own listening mic.
        if (action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND) {
            if (!OverlayCoordinator.locked) {
                com.carteltv.tv.voice.VoiceEngine.toggle(this)
            }
            return
        }
        val query = when (action) {
            "android.media.action.MEDIA_PLAY_FROM_SEARCH", Intent.ACTION_SEARCH -> i.getStringExtra(SearchManager.QUERY)
            Intent.ACTION_VIEW -> i.data?.takeIf { it.scheme == "carteltv" && it.host == "say" }?.getQueryParameter("q")
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return
        if (OverlayCoordinator.locked) return
        AppScope.io.launch { com.carteltv.tv.voice.VoicePipeline.submit(applicationContext, query.take(300), searchFallback = true, external = true, browsable = action == Intent.ACTION_VIEW) }
    }

    /**
     * Self deep link from our published Watch Next rows: `carteltv://watch?ref=<local ref>`.
     * The ref is resolved against local recents only — any caller-supplied stream URL is ignored.
     * Unresolvable refs land on the Recents screen instead of failing. True when the intent was ours.
     */
    private fun handleWatchLink(i: Intent?): Boolean {
        val raw = i?.data?.toString() ?: return false
        val ref = com.carteltv.tv.data.watch.WatchNextPublisher.refFromDeepLink(raw) ?: return false
        if (OverlayCoordinator.locked) return true
        val entry = com.carteltv.tv.data.watch.WatchNextPublisher.resolve(AppPreferences.recentEntries(), ref)
        if (entry != null) {
            com.carteltv.tv.nav.AppLauncher.player(this, entry.url, entry.title, ref)
        } else {
            Nav.resetTo(Route.Home)
            Nav.push(Route.Recents)
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistIntent(intent)
        if (handleWatchLink(intent)) return
        val goto = intent.getStringExtra("goto")
        if (goto != null) {
            pushRoute(goto)
            return
        }
        // SingleTask HOME reset: pressing Home always dismisses transient overlays and returns to root Home.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            OverlayCoordinator.closeTransient()
            showLogin = false
            MoreTimeApproval.ask = false
            Nav.resetTo(Route.Home)
            HomeReset.tick++
        }
    }

    private companion object {
        const val REQ_NOTIF = 50_001
    }
}