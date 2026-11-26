package com.carteltv.tv.data.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrobbleForTest {
    private val hour = 3_600_000L

    @Test fun movieCountsAtEightyPercent() {
        assertEquals(Scrobble.Movie("Heat"), scrobbleFor("movie:1", "Heat", hour * 8 / 10, hour))
    }

    @Test fun movieCountsAfterFiveMinutesEvenIfShort() {
        assertEquals(Scrobble.Movie("Heat"), scrobbleFor("movie:1", "Heat", 300_000, hour))
    }

    @Test fun earlyExitDoesNotScrobble() {
        assertNull(scrobbleFor("movie:1", "Heat", 120_000, hour))
    }

    @Test fun liveAndRecordingsNeverScrobble() {
        assertNull(scrobbleFor("live:5", "News", hour, hour))
        assertNull(scrobbleFor("recording:x", "Game", hour, hour))
    }

    @Test fun unknownDurationOrBlankRefNeverScrobbles() {
        assertNull(scrobbleFor("movie:1", "Heat", hour, 0))
        assertNull(scrobbleFor("", "Heat", hour, hour))
    }

    @Test fun seriesParsesShowSeasonEpisode() {
        assertEquals(Scrobble.Episode("The Wire", 3, 7), scrobbleFor("series:9", "The Wire S03E07", hour, hour))
        assertEquals(Scrobble.Episode("The Wire", 3, 7), scrobbleFor("series:9", "The Wire S3 E7 - Title", hour, hour))
    }

    @Test fun seriesWithoutEpisodeMarkerIsSkipped() {
        assertNull(scrobbleFor("series:9", "The Wire", hour, hour))
    }
}
