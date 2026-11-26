package com.carteltv.tv.engine.playback

import androidx.media3.common.C
import com.carteltv.tv.data.xtream.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrmPolicyTest {
    @Test fun mapsKnownSchemesToMedia3Uuids() {
        assertEquals(C.WIDEVINE_UUID, DrmPolicy.schemeUuid("widevine"))
        assertEquals(C.PLAYREADY_UUID, DrmPolicy.schemeUuid("PlayReady"))
        assertEquals(C.CLEARKEY_UUID, DrmPolicy.schemeUuid(" clearkey "))
    }

    @Test fun unknownSchemesAreIgnored() {
        assertNull(DrmPolicy.schemeUuid("fairplay"))
        assertNull(DrmPolicy.schemeUuid(""))
    }

    @Test fun missingSchemeProducesNoConfiguration() {
        assertNull(DrmPolicy.configuration(M3uParser.DrmInfo("unknown", "https://license.example"), emptyMap()))
    }
}
