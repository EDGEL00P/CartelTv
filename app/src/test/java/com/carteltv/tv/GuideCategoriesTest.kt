package com.carteltv.tv

import com.carteltv.tv.data.xtream.XtreamClient.Category
import com.carteltv.tv.ui.live.GuideCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideCategoriesTest {

    private fun key(name: String) = GuideCategories.bucketOf(name)?.key

    @Test fun providerGroupNamesLandInTheRightBucket() {
        assertEquals("news", key("US| NEWS HD"))
        assertEquals("sports", key("UK| SKY SPORTS"))
        assertEquals("sports", key("NFL Game Pass"))
        assertEquals("movies", key("CA| MOVIES 4K"))
        assertEquals("docs", key("US| Documentaries"))
        assertEquals("music", key("AU| Music"))
    }

    @Test fun theFirstMatchingBucketWins() {
        assertEquals("kids", key("US| Kids Movies"))
        assertEquals("sports", key("Sports News"))
    }

    @Test fun wordsMatchFromTheirStartOnly() {
        assertNull(key("TRANSPORT INFO"))
        assertEquals("sports", key("ESPN Plus"))
    }

    @Test fun onlyBucketsThatHaveGroupsAreOffered() {
        val groups = listOf(Category("1", "US| NEWS"), Category("2", "UK| SPORT"), Category("3", "XYZ"))
        assertEquals(listOf("sports", "news"), GuideCategories.present(groups).map { it.key })
        assertEquals(setOf("1"), GuideCategories.idsFor("news", groups))
    }

    @Test fun regionsBecomeASubRowOnlyWhenThereAreTwoOrMore() {
        val groups = listOf(Category("1", "US| NEWS HD"), Category("2", "UK| NEWS"), Category("3", "[us] News 4K"), Category("4", "CA| SPORT"))
        assertEquals(listOf("UK", "US"), GuideCategories.regionsFor("news", groups))
        assertEquals(emptyList<String>(), GuideCategories.regionsFor("sports", groups))
        assertEquals(setOf("1", "3"), GuideCategories.idsFor("news", groups, "US"))
    }

    @Test fun picksParseIntoBucketAndRegion() {
        assertEquals("news" to null, GuideCategories.parsePick("cat:news"))
        assertEquals("news" to "US", GuideCategories.parsePick("cat:news:US"))
        assertNull(GuideCategories.parsePick("fav"))
    }
}
