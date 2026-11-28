package com.carteltv.tv.data.watch

import com.carteltv.tv.data.preferences.WatchHistoryPreferences.RecentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextPublisherTest {

    private val hour = 3_600_000L

    private fun entry(
        ref: String,
        title: String = "Heat",
        url: String = "http://x/stream.mkv",
        poster: String = "http://x/p.jpg",
        pos: Long = hour / 2,
        dur: Long = hour,
    ) = RecentEntry(ref = ref, title = title, url = url, poster = poster, positionMs = pos, durationMs = dur)

    @Test fun refGrammarMapsToMovieAndEpisodeKinds() {
        assertEquals(WatchNextPublisher.Kind.Movie, WatchNextPublisher.watchKind("movie:7"))
        assertEquals(WatchNextPublisher.Kind.Movie, WatchNextPublisher.watchKind("tmdb:movie:603"))
        assertEquals(WatchNextPublisher.Kind.Episode, WatchNextPublisher.watchKind("series:Foo S01E02"))
        assertEquals(WatchNextPublisher.Kind.Episode, WatchNextPublisher.watchKind("tmdb:tv:1396"))
        assertNull(WatchNextPublisher.watchKind("live:5"))
        assertNull(WatchNextPublisher.watchKind("recording:x"))
        assertNull(WatchNextPublisher.watchKind("yt:abc"))
        assertNull(WatchNextPublisher.watchKind(""))
    }

    @Test fun unfinishedMoviePublishesAsContinueWithTypeAndSelfLink() {
        val plan = WatchNextPublisher.planFor(entry("movie:7")) as WatchNextPublisher.Plan.Publish
        assertEquals(WatchNextPublisher.PROGRAM_TYPE_MOVIE, plan.programType)
        assertEquals("movie:7", plan.ref)
        assertEquals("carteltv://watch?ref=movie%3A7", plan.intentUri)
        assertEquals(hour / 2, plan.positionMs)
        assertEquals(hour, plan.durationMs)
        assertEquals("http://x/p.jpg", plan.posterUri)
    }

    @Test fun episodePublishesWithEpisodeType() {
        val plan = WatchNextPublisher.planFor(entry("series:Foo S01E02", title = "Foo S01E02")) as WatchNextPublisher.Plan.Publish
        assertEquals(WatchNextPublisher.PROGRAM_TYPE_TV_EPISODE, plan.programType)
    }

    @Test fun completedEntriesAreRemovedNotPublished() {
        assertTrue(WatchNextPublisher.planFor(entry("movie:7", pos = hour)) is WatchNextPublisher.Plan.Remove)
        assertTrue(WatchNextPublisher.planFor(entry("movie:7", pos = hour * 95 / 100)) is WatchNextPublisher.Plan.Remove)
    }

    @Test fun nothingPublishesWithoutDurationPositionUrlOrKnownKind() {
        assertTrue(WatchNextPublisher.planFor(entry("movie:7", dur = 0)) is WatchNextPublisher.Plan.Ignore)
        assertTrue(WatchNextPublisher.planFor(entry("movie:7", pos = 0)) is WatchNextPublisher.Plan.Ignore)
        assertTrue(WatchNextPublisher.planFor(entry("movie:7", url = "")) is WatchNextPublisher.Plan.Ignore)
        assertTrue(WatchNextPublisher.planFor(entry("live:5")) is WatchNextPublisher.Plan.Ignore)
    }

    @Test fun nonHttpPosterArtIsDropped() {
        assertNull(WatchNextPublisher.sanitizePoster("file:///tmp/p.jpg"))
        assertNull(WatchNextPublisher.sanitizePoster(""))
        assertEquals("http://x/p.jpg", WatchNextPublisher.sanitizePoster("http://x/p.jpg"))
        assertEquals("https://x/p.jpg", WatchNextPublisher.sanitizePoster("https://x/p.jpg"))
    }

    @Test fun deepLinkRoundTripsRefsWithReservedCharacters() {
        for (ref in listOf("movie:7", "series:Foo S01E02", "tmdb:tv:1396")) {
            assertEquals(ref, WatchNextPublisher.refFromDeepLink(WatchNextPublisher.deepLink(ref)))
        }
    }

    @Test fun refFromDeepLinkRejectsOtherLinksAndMissingRefs() {
        assertNull(WatchNextPublisher.refFromDeepLink("carteltv://say?q=hello"))
        assertNull(WatchNextPublisher.refFromDeepLink("carteltv://watch"))
        assertNull(WatchNextPublisher.refFromDeepLink("carteltv://watch?ref="))
        assertNull(WatchNextPublisher.refFromDeepLink("https://evil.example/watch?ref=movie:7"))
        assertNull(WatchNextPublisher.refFromDeepLink(""))
    }

    @Test fun callerSuppliedStreamUrlsInLinksAreNeverSurfaceLevelResolvable() {
        // The link grammar carries no url; a hostile extra param is simply ignored.
        val ref = WatchNextPublisher.refFromDeepLink("carteltv://watch?ref=movie%3A7&url=http%3A%2F%2Fevil%2Fs.mkv")
        assertEquals("movie:7", ref)
    }

    @Test fun resolveOnlyMatchesLocalRecentsWithPlayableUrls() {
        val recents = listOf(
            entry("movie:7", url = "http://x/ok.mkv"),
            entry("movie:8", url = ""),
        )
        assertEquals("http://x/ok.mkv", WatchNextPublisher.resolve(recents, "movie:7")?.url)
        assertNull(WatchNextPublisher.resolve(recents, "movie:8"))
        assertNull(WatchNextPublisher.resolve(recents, "movie:9"))
    }

    @Test fun completionThresholdMatchesWatchHistoryFinished() {
        assertTrue(WatchNextPublisher.isComplete(hour, hour))
        assertTrue(WatchNextPublisher.isComplete(hour * 95 / 100, hour))
        assertTrue(!WatchNextPublisher.isComplete(hour * 94 / 100, hour))
        assertTrue(!WatchNextPublisher.isComplete(hour, 0))
        assertTrue(!WatchNextPublisher.isComplete(0, hour))
    }
}
