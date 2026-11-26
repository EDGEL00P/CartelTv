package com.carteltv.tv.ui.components
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Warms Coil's cache for the cards just past the visible end of a row, at the tile's own pixel size
 * so the later AsyncImage request hits the memory cache instead of decoding while the D-pad scrolls.
 */
@Composable
fun PrefetchAhead(state: LazyListState, urls: List<String?>, width: Dp, height: Dp, ahead: Int = 6) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val w = with(density) { width.roundToPx() }
    val h = with(density) { height.roundToPx() }
    LaunchedEffect(state, urls, w, h) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (last < 0) return@collect
                for (i in (last + 1)..minOf(last + ahead, urls.lastIndex)) {
                    val url = urls.getOrNull(i)?.takeIf { it.isNotBlank() } ?: continue
                    SingletonImageLoader.get(ctx).enqueue(ImageRequest.Builder(ctx).data(url).size(w, h).build())
                }
            }
    }
}
