package com.carteltv.tv.data.espn

import com.carteltv.tv.data.espn.PlayingSurface
import com.carteltv.tv.data.espn.surfaceForLeague
import com.carteltv.tv.data.espn.surfaceForPath
import com.carteltv.tv.data.espn.surfaceForSport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlayingSurfaceTest {

    @Test fun eachEspnSportMapsToItsSurface() {
        assertEquals(PlayingSurface.FOOTBALL, surfaceForPath("football/nfl"))
        assertEquals(PlayingSurface.FOOTBALL, surfaceForPath("football/college-football"))
        assertEquals(PlayingSurface.PITCH, surfaceForPath("soccer/eng.1"))
        assertEquals(PlayingSurface.PITCH, surfaceForPath("rugby/267979"))
        assertEquals(PlayingSurface.PITCH, surfaceForPath("australian-football/afl"))
        assertEquals(PlayingSurface.DIAMOND, surfaceForPath("baseball/mlb"))
        assertEquals(PlayingSurface.COURT, surfaceForPath("basketball/nba"))
        assertEquals(PlayingSurface.ICE, surfaceForPath("hockey/nhl"))
        assertEquals(PlayingSurface.CAGE, surfaceForPath("mma/ufc"))
        assertEquals(PlayingSurface.CRICKET, surfaceForPath("cricket/8048"))
        assertNull(surfaceForPath("curling/world"))
    }

    @Test fun leagueLabelsResolveThroughTheFeedList() {
        assertEquals(PlayingSurface.FOOTBALL, surfaceForLeague("NCAAF"))
        assertEquals(PlayingSurface.ICE, surfaceForLeague("NHL"))
        assertEquals(PlayingSurface.CAGE, surfaceForLeague("UFC"))
    }

    @Test fun everyLeagueTheAppListsHasASurfaceOrIsAKnownOddity() {
        val missing = LeagueCatalog.ALL_LEAGUES.filter { surfaceForPath(it.second) == null }.map { it.first }
        assertEquals("leagues with no playing surface: $missing", emptyList<String>(), missing)
    }

    @Test fun majorEventSportsMapToSurfaces() {
        assertEquals(PlayingSurface.CAGE, surfaceForSport("UFC"))
        assertEquals(PlayingSurface.RING, surfaceForSport("Boxing"))
        assertEquals(PlayingSurface.TRACK, surfaceForSport("Motorsport"))
        assertEquals(PlayingSurface.PITCH, surfaceForSport("Australian"))
        assertNotNull(surfaceForSport("nfl"))
        assertNull(surfaceForSport("Olympics"))
    }
}
