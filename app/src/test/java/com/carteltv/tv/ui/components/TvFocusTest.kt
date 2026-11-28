@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.carteltv.tv.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.carteltv.tv.TestBootApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestBootApp::class)
class TvFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun declinedFocusRequestReturnsFalse() {
        lateinit var requester: FocusRequester
        compose.setContent {
            requester = remember { FocusRequester() }
            Box(
                Modifier
                    .focusRequester(requester)
                    .focusProperties { canFocus = false }
                    .size(48.dp)
                    .focusable(),
            )
        }

        compose.runOnIdle { assertFalse(requester.tryFocus()) }
    }

    @Test
    fun acceptedFocusRequestReturnsTrue() {
        lateinit var requester: FocusRequester
        compose.setContent {
            requester = remember { FocusRequester() }
            Box(Modifier.focusRequester(requester).size(48.dp).focusable())
        }

        compose.runOnIdle { assertTrue(requester.tryFocus()) }
    }
}
