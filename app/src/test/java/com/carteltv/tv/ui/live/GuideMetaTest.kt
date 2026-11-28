package com.carteltv.tv.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** EPG titles carry noise the metadata lookup must not search with. */
class GuideMetaTest {

    @Test
    fun `strips a parenthesised year`() {
        assertEquals("The Matrix", GuideMetadata.cleanTitle("The Matrix (1999)"))
    }

    @Test
    fun `strips quality tags`() {
        assertEquals("Sky News", GuideMetadata.cleanTitle("Sky News HD"))
        assertEquals("Ocean Wonders", GuideMetadata.cleanTitle("Ocean Wonders 4K"))
    }

    @Test
    fun `strips separator tails`() {
        assertEquals("Premier League", GuideMetadata.cleanTitle("Premier League | Match Day"))
        assertEquals("Nova", GuideMetadata.cleanTitle("Nova \u2022 Secrets of the Deep"))
        assertEquals("Coronation Street", GuideMetadata.cleanTitle("Coronation Street \u2013 Double Episode"))
    }

    @Test
    fun `plain titles pass through`() {
        assertEquals("Friends", GuideMetadata.cleanTitle("Friends"))
    }

    @Test
    fun `programme labels follow the genre instead of the shared tint colour`() {
        assertEquals("NEWS", categoryLabel("The Late Report", "Live news and current affairs."))
        assertEquals("NATURE", categoryLabel("Horizon", "A documentary about the natural world."))
        assertEquals("KIDS", categoryLabel("Kids Club", null))
        assertEquals(null, categoryLabel("Friends", null))
    }
}
