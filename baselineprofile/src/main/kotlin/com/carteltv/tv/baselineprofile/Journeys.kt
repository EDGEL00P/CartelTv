package com.carteltv.tv.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope

const val TARGET_PACKAGE = "com.carteltv.tv"

/**
 * Cold start into Home, then D-pad around the rails the way a viewer does. Nothing is selected, so
 * the journey never opens a player or needs a signed-in provider.
 */
fun MacrobenchmarkScope.launchAndBrowseHome() {
    pressHome()
    startActivityAndWait()
    device.waitForIdle()
    repeat(4) { device.pressDPadDown(); device.waitForIdle() }
    repeat(6) { device.pressDPadRight(); device.waitForIdle() }
    repeat(3) { device.pressDPadDown(); device.waitForIdle() }
    repeat(6) { device.pressDPadLeft(); device.waitForIdle() }
    repeat(4) { device.pressDPadUp(); device.waitForIdle() }
}

/**
 * Cold start, open the guide, then page through it. Precompiles guide DB reads, program rendering,
 * and channel-card composition so the first guide open is instant.
 */
fun MacrobenchmarkScope.launchAndBrowseGuide() {
    pressHome()
    startActivityAndWait()
    device.waitForIdle()
    // Navigate from Home to Live TV (the dock's Live and Guide entries share GuideContent).
    device.pressDPadDown(); device.waitForIdle()
    device.pressDPadRight(); device.waitForIdle()
    device.pressDPadUp(); device.waitForIdle()
    device.waitForIdle()
    repeat(5) { device.pressDPadDown(); device.waitForIdle() }
    repeat(3) { device.pressDPadRight(); device.waitForIdle() }
}

/**
 * Cold start, open Live TV, then exercise channel-up/down handling. Precompiles guide composition
 * and remote routing; with a configured provider this also reaches the channel list.
 */
fun MacrobenchmarkScope.launchAndChannelZap() {
    pressHome()
    startActivityAndWait()
    device.waitForIdle()
    device.pressDPadDown(); device.waitForIdle()  // dock
    device.pressDPadRight(); device.waitForIdle() // Live TV
    device.pressDPadUp(); device.waitForIdle()    // page content
    device.waitForIdle()
    repeat(3) {
        device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP); device.waitForIdle()
        device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN); device.waitForIdle()
    }
}
