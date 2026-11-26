package com.carteltv.tv.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the keyless radar provider contract without a device or network: parsing, timeline indexing and tile URLs. */
class RadarManifestTest {
    private val now = 1_800_000_000_000L

    private fun feed(nowcast: Boolean = true): String = buildString {
        append("""{"version":"2.0","generated":1795406993,"host":"https://api.librewxr.net","radar":{"past":[""")
        append("""{"time":100,"path":"/v2/radar/a"},{"time":200,"path":"/v2/radar/b"}""")
        if (nowcast) {
            append("""],"nowcast":[""")
            append((300..700 step 100).joinToString(",") { """{"time":$it,"path":"/v2/radar/f$it"}""" })
            append("""]""")
        }
        append(if (nowcast) """}}""" else """]}}""")
    }

    private val rainViewer = """{"host":"https://tilecache.rainviewer.com","radar":{"past":[{"time":100,"path":"/v2/radar/hash"}]}}"""

    @Test fun libreWxrManifestKeepsForecastBehindObservations() {
        val m = RadarFeed.parseManifest(feed(), RadarFeed.Provider.LIBREWXR, now)!!
        assertEquals(RadarFeed.Provider.LIBREWXR, m.provider)
        assertEquals("https://api.librewxr.net", m.host)
        assertEquals(listOf(100L, 200L), m.past.map { it.timeSec })
        // Five frames are advertised, but the timeline is deliberately capped to keep tile traffic bounded.
        assertEquals(listOf(300L, 400L, 500L, 600L), m.nowcast.map { it.timeSec })
        assertEquals(6, m.frames.size)
        assertEquals(1, m.latestIndex)
        assertTrue(m.hasNowcast)
    }

    @Test fun rainViewerManifestHasNoForecastFrames() {
        val m = RadarFeed.parseManifest(rainViewer, RadarFeed.Provider.RAINVIEWER, now)!!
        assertEquals(0, m.nowcast.size)
        assertEquals(1, m.frames.size)
        assertEquals(0, m.latestIndex)
        assertFalse(m.hasNowcast)
    }

    @Test fun malformedManifestsDegradeRatherThanCrash() {
        assertNull(RadarFeed.parseManifest("not json", RadarFeed.Provider.LIBREWXR, now))
        assertNull(RadarFeed.parseManifest("""{"host":"https://api.librewxr.net"}""", RadarFeed.Provider.LIBREWXR, now))
        assertNull(RadarFeed.parseManifest("""{"radar":{"past":[{"time":1,"path":"/p"}]}}""", RadarFeed.Provider.LIBREWXR, now))
        val partial = """{"host":"https://api.librewxr.net","radar":{"past":[{"time":100,"path":"/v2/radar/a"},{"nope":true}]}}"""
        assertEquals(1, RadarFeed.parseManifest(partial, RadarFeed.Provider.LIBREWXR, now)!!.past.size)
    }

    @Test fun pastFramesAreCappedAtTwelve() {
        val many = (1..20).joinToString(",") { """{"time":$it,"path":"/v2/radar/p$it"}""" }
        val m = RadarFeed.parseManifest("""{"host":"https://api.librewxr.net","radar":{"past":[$many]}}""", RadarFeed.Provider.LIBREWXR, now)!!
        assertEquals(RadarFeed.MAX_FRAMES, m.past.size)
        assertEquals(9L, m.past.first().timeSec)
    }

    @Test fun legendLevelsCarryRealReflectivityValues() {
        assertEquals(10, RadarFeed.dbzByLevel.size)
        assertEquals(2, RadarFeed.dbz(1))
        assertEquals(37, RadarFeed.dbz(6))
        assertEquals(67, RadarFeed.dbz(10))
        assertEquals(RadarFeed.dbz(10), RadarFeed.dbz(99))
    }

    @Test fun playbackStopsAtTheLastFrameInsteadOfWrapping() {
        assertEquals(1, RadarFeed.nextFrame(0, 4))
        assertEquals(3, RadarFeed.nextFrame(2, 4))
        assertNull(RadarFeed.nextFrame(3, 4))
        assertNull(RadarFeed.nextFrame(0, 0))
    }

    @Test fun flowOverlayIsRequestedOnlyFromLibreWxr() {
        val lib = RadarFeed.parseManifest(feed(false), RadarFeed.Provider.LIBREWXR, now)!!
        val rv = RadarFeed.parseManifest(rainViewer, RadarFeed.Provider.RAINVIEWER, now)!!
        assertEquals("https://api.librewxr.net/v2/radar/a/256/6/3/4/6/1_1.png", RadarFeed.radarUrl(lib, lib.past[0], 6, 3, 4))
        assertEquals(
            "https://api.librewxr.net/v2/radar/a/256/6/3/4/6/1_1.png?arrows=light",
            RadarFeed.radarUrl(lib, lib.past[0], 6, 3, 4, true),
        )
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/hash/256/7/3/4/6/1_1.png",
            RadarFeed.radarUrl(rv, rv.past[0], 7, 3, 4, true),
        )
    }
}
