package com.carteltv.tv.ui.catalog

import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.xtream.XtreamClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The On Demand browse rules: what a genre keeps, how a rail orders itself, where a letter jump
 * lands, what a focused poster says, and whose history stands on Continue watching.
 */
class BrowseControlsTest {

    @Test
    fun featured_keeps_the_source_s_own_order() {
        val sorted = sortedForBrowse(listOf("Zebra", "Alpha", "Mid"), CatalogSort.FEATURED) { CatalogSortKey(it) }
        assertEquals(listOf("Zebra", "Alpha", "Mid"), sorted)
    }

    @Test
    fun az_sorts_by_name_either_way_cased() {
        val sorted = sortedForBrowse(listOf("Zebra", "alpha", "Mid"), CatalogSort.AZ) { CatalogSortKey(it) }
        assertEquals(listOf("alpha", "Mid", "Zebra"), sorted)
    }

    @Test
    fun top_rated_puts_the_best_first_and_an_unrated_title_last() {
        val items = listOf("Fair" to 6.0, "Great" to 9.0, "Unrated" to null)
        val sorted = sortedForBrowse(items, CatalogSort.TOP_RATED) { (t, r) -> CatalogSortKey(t, r) }
        assertEquals(listOf("Great", "Fair", "Unrated"), sorted.map { it.first })
    }

    @Test
    fun a_sort_never_swaps_titles_the_source_ranks_equal() {
        val items = listOf("B" to 7.0, "A" to 7.0, "C" to 7.0)
        val sorted = sortedForBrowse(items, CatalogSort.TOP_RATED) { (t, r) -> CatalogSortKey(t, r) }
        assertEquals(listOf("B", "A", "C"), sorted.map { it.first })
    }

    @Test
    fun genre_chips_are_the_categories_tidied_and_unique() {
        val cats = listOf(
            XtreamClient.Category("1", "[EN] DISNEY"),
            XtreamClient.Category("2", "disney"),
            XtreamClient.Category("3", "ACTION"),
        )
        assertEquals(listOf("Disney", "Action"), genreChips(cats))
    }

    @Test
    fun a_genre_pick_keeps_only_its_own_rails_and_all_keeps_every_one() {
        val cats = listOf(XtreamClient.Category("1", "Action"), XtreamClient.Category("2", "Drama"))
        assertEquals(cats, visibleCategoriesFor(cats, null))
        assertEquals(listOf(cats[1]), visibleCategoriesFor(cats, "Drama"))
        // The chip and the rail need not agree on how the source cased the name.
        assertEquals(listOf(cats[1]), visibleCategoriesFor(cats, "drama"))
    }

    @Test
    fun the_strip_lists_every_letter_some_title_files_under_digits_as_hash() {
        assertEquals(listOf('#', 'A', 'B', 'Z'), azIndex(listOf("21 Jump Street", "Alien", "zootopia", "Bound")))
        assertEquals(emptyList<Char>(), azIndex(listOf("", "  ", "…")))
    }

    @Test
    fun a_letter_jump_lands_on_the_first_rail_and_tile_filing_under_it() {
        val rails = listOf(listOf("Alpha", "Beta"), listOf("Bear", "Zulu"), listOf("Cats"))
        assertEquals(0 to 0, azJump('A', rails))
        assertEquals(0 to 1, azJump('B', rails))
        assertEquals(2 to 0, azJump('C', rails))
        assertEquals(1 to 1, azJump('Z', rails))
    }

    @Test
    fun a_letter_nothing_files_under_has_nowhere_to_jump() {
        assertNull(azJump('Y', listOf(listOf("Alpha"), listOf("Beta"))))
    }

    private fun entry(ref: String, title: String, url: String = "https://example.com/v.mp4") =
        AppPreferences.RecentEntry(ref = ref, title = title, url = url)

    @Test
    fun movies_take_only_film_plays_with_a_playable_url() {
        val recents = listOf(
            entry("movie:7", "The Matrix"),
            entry("stremio:tt0111161", "The Shawshank Redemption"),
            entry("series:3/9", "The Bear S1 E3"),
            entry("live:5", "ESPN"),
            entry("movie:9", "Unplayable", url = ""),
        )
        assertEquals(
            listOf("The Matrix", "The Shawshank Redemption"),
            catalogContinueWatching(recents, movieCatalog = true).map { it.title },
        )
    }

    @Test
    fun shows_take_only_series_plays() {
        val recents = listOf(
            entry("series:3/9", "The Bear S1 E3"),
            entry("series:tt0944947/1:1", "Shogun S1 E1"),
            entry("movie:7", "The Matrix"),
            entry("stremio:tt0111161", "Shawshank"),
        )
        assertEquals(
            listOf("The Bear S1 E3", "Shogun S1 E1"),
            catalogContinueWatching(recents, movieCatalog = false).map { it.title },
        )
    }

    @Test
    fun one_card_per_title_however_many_episodes_the_one_played_last_stands_for_it() {
        val recents = listOf(entry("series:3/9", "The Bear S1 E3"), entry("series:3/8", "The Bear S1 E2"))
        assertEquals(listOf("The Bear S1 E3"), catalogContinueWatching(recents, movieCatalog = false).map { it.title })
    }

    @Test
    fun the_same_title_counts_once_however_it_is_spelled() {
        val recents = listOf(entry("movie:1", "The Bear!"), entry("movie:2", "the bear"))
        assertEquals(listOf("The Bear!"), catalogContinueWatching(recents, movieCatalog = true).map { it.title })
    }

    @Test
    fun continue_watching_stops_at_twelve_cards_in_recency_order() {
        val recents = (1..20).map { entry("movie:$it", "Film $it") }
        val shown = catalogContinueWatching(recents, movieCatalog = true)
        assertEquals(12, shown.size)
        assertEquals(listOf("Film 1", "Film 12"), listOf(shown.first().title, shown.last().title))
    }

    @Test
    fun a_rating_string_becomes_a_one_decimal_star_label() {
        assertEquals("★ 7.4", ratingLabel("7.44"))
        assertEquals("★ 8.0", ratingLabel("8"))
        assertNull(ratingLabel("N/A"))
        assertNull(ratingLabel(null))
    }

    @Test
    fun a_title_s_bracketed_year_is_read_where_the_provider_has_no_year_field() {
        assertEquals("1999", titleYear("The Matrix (1999)"))
        assertEquals("2010", titleYear("Film [2010] 4K"))
        assertNull(titleYear("Blade Runner"))
        assertNull(titleYear("Se7en"))
    }

    @Test
    fun the_poster_caption_joins_whatever_metadata_the_source_has() {
        assertEquals("1999 · ★ 7.4", posterMeta("1999", "★ 7.4"))
        assertEquals("1999", posterMeta("1999", null))
        assertNull(posterMeta(null, null))
    }
}
