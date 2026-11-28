package com.carteltv.tv.nav

import androidx.compose.ui.focus.FocusRequester
import com.carteltv.tv.ui.nav.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tab switches must keep each dock section's own back stack: leave a tab, come back, same place. */
class TabBackStackTest {
    @Test
    fun `switching tabs saves the old tab stack and restores the new tab's own`() {
        Nav.resetTo(Route.Home)
        Nav.push(Route.Movies)
        Nav.push(Route.Movie(1L, "T", "mp4", null, null))

        SectionNav.go(AppSection.LIVE)
        assertEquals(listOf<Route>(Route.Home, Route.LiveTv), Nav.stack.toList())
        Nav.push(Route.Guide)
        SectionNav.go(AppSection.MOVIES)
        assertEquals(listOf<Route>(Route.Home, Route.Movies, Route.Movie(1L, "T", "mp4", null, null)), Nav.stack.toList())
    }

    @Test
    fun `a tab never visited roots at its own route`() {
        Nav.resetTo(Route.Home)
        Nav.push(Route.Movies)
        SectionNav.go(AppSection.SPORTS)
        assertEquals(listOf<Route>(Route.Home, Route.Scores), Nav.stack.toList())
    }

    @Test
    fun `detail routes ride the stack of the tab they were opened from`() {
        Nav.resetTo(Route.Home)
        Nav.push(Route.Movies)
        Nav.push(Route.Series(7L, "S", null, null))
        SectionNav.go(AppSection.HOME)
        assertEquals(listOf<Route>(Route.Home), Nav.stack.toList())
        SectionNav.go(AppSection.MOVIES)
        assertEquals(listOf<Route>(Route.Home, Route.Movies, Route.Series(7L, "S", null, null)), Nav.stack.toList())
    }

    @Test
    fun `going to the current tab does nothing`() {
        Nav.resetTo(Route.Home)
        Nav.push(Route.Movies)
        SectionNav.go(AppSection.MOVIES)
        assertEquals(listOf<Route>(Route.Home, Route.Movies), Nav.stack.toList())
    }

    @Test
    fun `back after switching tabs pops within the new tab`() {
        Nav.resetTo(Route.Home)
        Nav.push(Route.Movies)
        SectionNav.go(AppSection.WEATHER)
        Nav.push(Route.Movie(2L, "T", null, null, null))
        assertEquals(true, Nav.pop())
        assertEquals(Route.Weather, Nav.current)
    }

    @Test
    fun `back clears the disposed route focus requester`() {
        Nav.resetTo(Route.Home)
        Nav.push(Route.Movie(1L, "T", null, null, null))
        ShellChrome.lastContentFocus = FocusRequester()

        assertEquals(true, Nav.pop())
        assertNull(ShellChrome.lastContentFocus)
    }

    @Test
    fun `every route has a stable save key`() {
        assertEquals("home", Route.Home.stateKey)
        assertEquals("movie_9", Route.Movie(9L, "T", null, null, null).stateKey)
        assertEquals("series_4", Route.Series(4L, "S", null, null).stateKey)
        assertEquals("player_r1", Route.Player("u", "T", "r1").stateKey)
    }
}
