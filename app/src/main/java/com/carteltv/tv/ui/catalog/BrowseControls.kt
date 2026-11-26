package com.carteltv.tv.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.carteltv.tv.R
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.stremio.StremioClient
import com.carteltv.tv.data.xtream.XtreamClient
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.nav.Route
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.rememberAppStarted
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.tidyRailTitle
import com.carteltv.tv.ui.home.splitTitle
import com.carteltv.tv.ui.home.titleKey
import com.carteltv.tv.ui.live.GuideMetadata
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.core.suspendRunCatching
import java.util.Locale

/** The order a rail's titles take: the source's own, alphabetical, or best-rated first. */
internal enum class CatalogSort { FEATURED, AZ, TOP_RATED }

/** What a browse item reduces to for ordering: its name, and the rating the source carries. */
internal data class CatalogSortKey(val title: String, val rating: Double? = null)

/** [items] in [sort] order. FEATURED keeps the source's own order; the other two are stable, so titles the
 *  source ranks equal never swap places under them. */
internal fun <T> sortedForBrowse(items: List<T>, sort: CatalogSort, key: (T) -> CatalogSortKey): List<T> = when (sort) {
    CatalogSort.FEATURED -> items
    CatalogSort.AZ -> items.sortedBy { key(it).title.lowercase() }
    CatalogSort.TOP_RATED -> items.sortedByDescending { key(it).rating ?: Double.NEGATIVE_INFINITY }
}

/** The number a provider's rating string carries, when it is a number at all. */
internal fun ratingValue(rating: String?): Double? = rating?.trim()?.toDoubleOrNull()

/** The star label a rating becomes ("7.44" → "★ 7.4"): one decimal, always a dot, whatever the device's locale. */
internal fun ratingLabel(rating: String?): String? =
    ratingValue(rating)?.let { "★ " + String.format(Locale.ROOT, "%.1f", it) }

/** The sort key of a Stremio catalog item: its name and its IMDb rating. */
internal fun metumSortKey(m: StremioClient.Metum): CatalogSortKey = CatalogSortKey(m.name, ratingValue(m.imdbRating))

/** A provider category as the genre chips know it: the rail title tidied ("[EN] DISNEY" → "Disney"). */
internal fun genreOf(category: XtreamClient.Category): String = tidyRailTitle(category.category_name)

/** The genre chips: every provider category name, tidied, one chip however the source cased it. */
internal fun genreChips(categories: List<XtreamClient.Category>): List<String> =
    categories.map(::genreOf).filter { it.isNotBlank() }.distinctBy { it.lowercase() }

/** The provider categories a genre pick leaves on the page: its own rails — matched however the source
 *  cased the name — or every one of them for All. */
internal fun visibleCategoriesFor(
    categories: List<XtreamClient.Category>,
    genre: String?,
): List<XtreamClient.Category> =
    if (genre == null) categories else categories.filter { genreOf(it).equals(genre, ignoreCase = true) }

/** The letter a title files under: its first letter or digit, uppercased — digits file under '#'. */
internal fun azLetterOf(title: String): Char? {
    val first = title.trim().firstOrNull { it.isLetterOrDigit() } ?: return null
    return if (first.isDigit()) '#' else first.uppercaseChar()
}

/** The strip a page offers: every letter at least one title files under, '#' first. */
internal fun azIndex(titles: List<String>): List<Char> = titles.mapNotNull(::azLetterOf).distinct().sorted()

/** Where a letter jump lands: the first rail and tile filing under [letter] — or null when none does, which
 *  is the only case in which the strip does not offer the letter at all. */
internal fun azJump(letter: Char, titlesByRail: List<List<String>>): Pair<Int, Int>? {
    titlesByRail.forEachIndexed { rail, titles ->
        val tile = titles.indexOfFirst { azLetterOf(it) == letter }
        if (tile >= 0) return rail to tile
    }
    return null
}

/** One rail of history is enough: a dozen cards covers a sitting, the way Netflix's own row caps itself. */
internal const val CONTINUE_WATCHING_CAP = 12

/** The refs each On Demand catalog resumes. Xtream films play under "movie:", Stremio films under "stremio:",
 *  and every episode — Xtream or Stremio — under "series:", so the Movies page takes both film prefixes and
 *  the Shows page the series one. */
private val MOVIE_REF_PREFIXES = listOf("movie:", "stremio:")

/** The catalog's Continue watching: its own kind of plays, each with a playable URL, one card per title
 *  however the episode spelled it, in the history's own recency order, capped at [CONTINUE_WATCHING_CAP]. */
internal fun catalogContinueWatching(
    recents: List<AppPreferences.RecentEntry>,
    movieCatalog: Boolean,
    limit: Int = CONTINUE_WATCHING_CAP,
): List<AppPreferences.RecentEntry> {
    val prefixes = if (movieCatalog) MOVIE_REF_PREFIXES else listOf("series:")
    return recents
        .filter { e -> e.url.isNotBlank() && prefixes.any { e.ref.startsWith(it) } }
        .distinctBy { titleKey(splitTitle(it.title).name) }
        .take(limit)
}

/** Continue watching as the browse page holds it: the cards, and the removal a long-press asks for. */
internal data class ContinueWatching(val entries: List<AppPreferences.RecentEntry>, val onRemoved: (String) -> Unit)

/** The catalog's Continue watching, fresh whenever the player notes a playback (the host Activity resumes)
 *  or a card is removed — the same refresh Home's own row uses. */
@Composable
internal fun rememberContinueWatching(movieCatalog: Boolean): ContinueWatching {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val entries = remember(tick, movieCatalog) { catalogContinueWatching(AppPreferences.recentEntries(), movieCatalog) }
    return ContinueWatching(entries, onRemoved = { ref -> AppPreferences.removeRecentByRef(ref); tick++ })
}

/** Portrait art for history the player saved without a poster: the Cinemeta/TMDB lookup Home's Continue row
 *  shares, poster first because this rail's tiles are 2:3. Silent on a miss — the tile's monogram stands in. */
@Composable
internal fun rememberCwArt(entries: List<AppPreferences.RecentEntry>): Map<String, String> {
    var art by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val started = rememberAppStarted()
    LaunchedEffect(started, entries) {
        if (!started) return@LaunchedEffect
        for (e in entries.filter { it.poster.isBlank() }.distinctBy { GuideMetadata.cwArtKey(it.title) }) {
            val key = GuideMetadata.cwArtKey(e.title)
            if (art.containsKey(key)) continue
            val rich = suspendRunCatching { GuideMetadata.lookup(GuideMetadata.cwLookupTitle(e.title)) }.getOrNull()
            val url = rich?.let { it.poster ?: it.backdrop } ?: continue
            art = art + (key to url)
        }
    }
    return art
}

/** The year an IPTV title carries in brackets — "(1999)" or "[1999]" — the only year a film listing has. */
internal fun titleYear(raw: String): String? =
    TITLE_BRACKET_YEAR.find(raw)?.value?.filter { it.isDigit() }

private val TITLE_BRACKET_YEAR = Regex("""[(\[](?:19|20)\d{2}[)\]]""")

/** The focused-poster caption a provider tile shows: its parts joined — "1999 · ★ 7.4" — or null when the
 *  source carried none of them. */
internal fun posterMeta(vararg parts: String?): String? =
    parts.filterNotNull().joinToString(" · ").ifBlank { null }

/** Where the toolbar's rows start: the rails' own left inset, so the chips line up with the posters below. */
private val ToolbarGutter = ThemeSpec.Space.margin + ThemeSpec.Space.sm

/**
 * The On Demand toolbar, on screen above the scrolling catalog the whole time: browse never means losing the
 * controls (NN/g: horizontal-only catalogs fatigue). Search, the sort order, the genre chips and the A–Z
 * strip each hold focus in their own row; nothing here is dead — a control with nothing to act on is simply
 * not composed.
 */
@Composable
internal fun BrowseToolbar(
    genres: List<String>,
    genre: String?,
    onGenre: (String?) -> Unit,
    sort: CatalogSort,
    onSort: (CatalogSort) -> Unit,
    letters: List<Char>,
    letter: Char?,
    onLetter: (Char?) -> Unit,
) {
    val all = stringResource(R.string.filter_all)
    Column(
        Modifier.fillMaxWidth().padding(top = ThemeSpec.Space.md),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
    ) {
        ChipsRow {
            item(key = "nav:search") { ActionButton(stringResource(R.string.nav_search), filled = false) { Nav.push(Route.Search) } }
            item(key = "nav:library") { ActionButton(stringResource(R.string.nav_library), filled = false) { Nav.push(Route.Library) } }
            item(key = "nav:discover") { ActionButton(stringResource(R.string.nav_discover), filled = false) { Nav.push(Route.Discover) } }
            item(key = "nav:shelves") { ActionButton(stringResource(R.string.nav_shelves), filled = false) { Nav.push(Route.Shelves) } }
            item(key = "sort:featured") { FilterChip(stringResource(R.string.catalog_sort_featured), sort == CatalogSort.FEATURED) { onSort(CatalogSort.FEATURED) } }
            item(key = "sort:az") { FilterChip(stringResource(R.string.catalog_sort_az), sort == CatalogSort.AZ) { onSort(CatalogSort.AZ) } }
            item(key = "sort:top") { FilterChip(stringResource(R.string.catalog_sort_top_rated), sort == CatalogSort.TOP_RATED) { onSort(CatalogSort.TOP_RATED) } }
        }
        if (genres.isNotEmpty()) {
            ChipsRow {
                item(key = "genre:all") { FilterChip(all, genre == null) { onGenre(null) } }
                items(genres, key = { "genre:$it" }) { g -> FilterChip(g, genre == g) { onGenre(g) } }
            }
        }
        // The strip appears once more than one letter is in play (a one-letter page needs no jump), and stays
        // while a letter is picked so "All" can always let it go. Letters without a single title are never
        // composed, so every key on the strip scrolls somewhere real.
        if (letters.size >= 2 || (letter != null && letters.isNotEmpty())) {
            ChipsRow(spacing = ThemeSpec.Space.sm) {
                item(key = "az:all") { FilterChip(all, letter == null, compact = true) { onLetter(null) } }
                items(letters, key = { "az:$it" }) { l -> FilterChip(l.toString(), letter == l, compact = true) { onLetter(l) } }
            }
        }
    }
}

/** A toolbar chips line: focusable horizontally, remembered when the row regains focus, padded to the
 *  rails' own gutter so the chips sit over the posters they act on. */
@Composable
private fun ChipsRow(
    spacing: Dp = ThemeSpec.Space.md,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().restoreRowFocus(),
        contentPadding = PaddingValues(start = ToolbarGutter, end = ThemeSpec.Safe.horizontal + ThemeSpec.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
