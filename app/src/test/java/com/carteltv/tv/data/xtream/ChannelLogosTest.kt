package com.carteltv.tv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLogosTest {

    // These pairs are also exercised by tools/build_logo_index.py fold_name; keep them in step.
    @Test fun foldMatchesTheGenerator() {
        assertEquals("tsn1", ChannelLogos.fold("TSN 1 HD"))
        assertEquals("icitele", ChannelLogos.fold("ICI Télé"))
        assertEquals("ctvplus1", ChannelLogos.fold("CTV +1"))
        assertEquals("espn", ChannelLogos.fold("ESPN 4K FHD"))
        assertEquals("cnn", ChannelLogos.fold("c.n.n"))
    }

    @Test fun exactCountryWins() {
        assertEquals("ca", ChannelLogos.pick(mapOf("CA" to "ca", "US" to "us"), "CA"))
    }

    @Test fun noCountryAcceptsOnlyAnUnambiguousLogo() {
        assertEquals("same", ChannelLogos.pick(mapOf("AU" to "same", "BR" to "same"), null))
        assertNull(ChannelLogos.pick(mapOf("CA" to "a", "US" to "b"), null))
        assertNull(ChannelLogos.pick(mapOf("CA" to "a", "US" to "b"), "UK"))
        assertNull(ChannelLogos.pick(null, "CA"))
    }

    @Test fun urlForUsesProviderNameCleanupAndCountryTag() {
        ChannelLogos.installForTest(mapOf("tsn1" to mapOf("CA" to "ca-logo", "MT" to "mt-logo")))
        assertEquals("ca-logo", ChannelLogos.urlFor("CA| TSN 1 HD"))
        assertEquals("mt-logo", ChannelLogos.urlFor("MT| TSN 1 FHD"))
        assertNull(ChannelLogos.urlFor("TSN 1"))
        assertNull(ChannelLogos.urlFor("CA| TSN 2"))
    }

    @Test fun replacingTheIndexInvalidatesCachedNames() {
        ChannelLogos.installForTest(mapOf("cnn" to mapOf("US" to "old-logo")))
        assertEquals("old-logo", ChannelLogos.urlFor("CNN"))
        ChannelLogos.installForTest(mapOf("cnn" to mapOf("US" to "new-logo")))
        assertEquals("new-logo", ChannelLogos.urlFor("CNN"))
    }
}
