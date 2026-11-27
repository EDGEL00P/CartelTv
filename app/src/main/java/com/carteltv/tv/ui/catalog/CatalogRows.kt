package com.carteltv.tv.ui.catalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carteltv.tv.data.stremio.StremioAddons
import com.carteltv.tv.data.stremio.StremioClient
import com.carteltv.tv.ui.components.PosterTile
import com.carteltv.tv.ui.components.SectionTitle
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.hero.HeroItem
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val MAX_ROWS = 12
private const val ROW_ITEMS = 30

/** Vertical room a rail keeps above and below its posters so a focused tile's lift is never clipped. */
internal val RailContentHeadroom = 36.dp

/**
 * One catalog shelf shared by every On Demand page: a heading with an optional count, then a horizontal
 * poster rail with the house focus line, feathered edges and focus headroom. The page's own column owns the
 * horizontal inset, so the rail only adds the vertical headroom — posters and billboards line up on one gutter.
 */
@Composable
internal fun PosterRail(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    content: LazyListScope.() -> Unit,
) {
    val rowState = rememberLazyListState()
    Column(modifier.fillMaxWidth()) {
        SectionTitle(title, Modifier.padding(top = ThemeSpec.Space.xs, bottom = ThemeSpec.Space.xs), note = count?.let { "$it titles" })
        LazyRow(
            state = rowState,
            modifier = Modifier.restoreRowFocus().railEdgeFade(rowState),
            contentPadding = PaddingValues(vertical = RailContentHeadroom),
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
            content = content,
        )
    }
}

internal data class CatalogRow(
    val title: String,
    val items: List<StremioClient.Metum>,
)

internal fun StremioClient.Metum.toHero() =
    HeroItem(
        title = name,
        art = background ?: poster,
        description = description,
        badge =
            listOfNotNull(
                releaseInfo,
                imdbRating?.takeIf { it.isNotBlank() },
                runtime,
                genres.take(2).joinToString(", ").ifBlank { null },
            ).joinToString("  ·  ").ifBlank { null },
        refId = "stremio:$id",
        url = "",
    )

/** One row per installed-add-on catalog of [type] that needs no required extra (e.g. search). */
internal suspend fun loadCatalogRows(
    type: String,
    maxRows: Int = MAX_ROWS,
): List<CatalogRow> {
    val label = if (type == "series") "Series" else "Movies"
    val addons = StremioAddons.refresh()
    return coroutineScope {
        addons
            .flatMap { addon ->
                addon.manifest.catalogs
                    .filter { it.type == type && it.extra.none { e -> e.isRequired } }
                    .map { cd ->
                        async {
                            val items =
                                try {
                                    addon.client.catalog(type, cd.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            val base = (cd.name ?: cd.id).trim()
                            val title = if (base.contains(label, ignoreCase = true)) base else "$base $label"
                            CatalogRow(title, items.take(ROW_ITEMS))
                        }
                    }
            }.take(maxRows)
            .awaitAll()
            .filter { it.items.isNotEmpty() }
    }
}

/** The Stremio rail tile: portrait poster art at rest, with year, rating and first genre revealing under the focused tile. */
@Composable
internal fun LabeledPoster(
    m: StremioClient.Metum,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val meta =
        listOfNotNull(
            m.releaseInfo?.take(4)?.takeIf { it.isNotBlank() },
            m.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
            m.genres.firstOrNull()?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    PosterTile(
        // Poster first — landscape backdrops crop badly inside 2:3 art.
        imageUrl = m.poster ?: m.background,
        title = m.name,
        meta = meta,
        modifier = modifier,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
