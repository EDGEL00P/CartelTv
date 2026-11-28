package com.carteltv.tv.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carteltv.tv.TestBootApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestBootApp::class)
class SettingsRowsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledToggleRowCannotBeActivated() {
        var clicks = 0
        compose.setContent {
            ToggleRow(title = "Reduce transparency", desc = null, value = true, focus = null, enabled = false) {
                clicks++
            }
        }

        val row = compose.onNodeWithText("Reduce transparency")
        row.assertIsNotEnabled()
        row.performClick()
        assertEquals(0, clicks)
    }
}