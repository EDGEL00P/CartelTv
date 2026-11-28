package com.carteltv.tv.ui.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodBrowseTest {
    @Test
    fun `provider rows keep the page loading until their sequential requests finish`() {
        assertTrue(
            catalogGridLoading(
                hasProviderItems = false,
                hasStremioItems = false,
                providerLoadComplete = false,
                stremioLoaded = true,
            ),
        )
    }

    @Test
    fun `an empty page is shown only after every source has finished`() {
        assertFalse(
            catalogGridLoading(
                hasProviderItems = false,
                hasStremioItems = false,
                providerLoadComplete = true,
                stremioLoaded = true,
            ),
        )
    }

    @Test
    fun `ready provider content is not held back by slower add-ons`() {
        assertFalse(
            catalogGridLoading(
                hasProviderItems = true,
                hasStremioItems = false,
                providerLoadComplete = false,
                stremioLoaded = false,
            ),
        )
    }
}