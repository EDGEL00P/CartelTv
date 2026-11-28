package com.carteltv.tv.ui.recents

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.carteltv.tv.data.apps.AppLaunchHistory
import com.carteltv.tv.data.apps.AppsRepository
import com.carteltv.tv.data.apps.InstalledAppsCache
import com.carteltv.tv.data.apps.LauncherApp
import com.carteltv.tv.ui.components.Chip
import com.carteltv.tv.ui.components.DpadKeys
import com.carteltv.tv.ui.components.EmptyState
import com.carteltv.tv.ui.components.PageLoading
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.railEdgeFade
import com.carteltv.tv.ui.components.railWrapItem
import com.carteltv.tv.ui.components.railWrapToStart
import com.carteltv.tv.ui.components.rememberAppIcon
import com.carteltv.tv.ui.components.rememberRailWrapState
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.nav.PagePadding
import com.carteltv.tv.ui.nav.ScreenPage
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.theme.*
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native TV Recent Apps Switcher.
 *
 * Shows recently foregrounded apps as large glass cards side-by-side:
 *  - OK launches the app and dismisses the switcher
 *  - DPAD UP closes the app's background process and removes the card
 *  - BACK dismisses
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RecentsOverlay(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val firstCard = remember { FocusRequester() }
    var items by remember { mutableStateOf<List<LauncherApp>?>(null) }
    val dismissed = remember { mutableStateListOf<String>() }

    BackHandler { onClose() }

    LaunchedEffect(Unit) {
        val pkgs = withContext(Dispatchers.IO) { AppLaunchHistory.recentPackages(ctx) }
        val known = InstalledAppsCache.apps.associateBy { it.packageName }
        items = pkgs.mapNotNull { known[it] }
    }

    val visible = items.orEmpty().filter { it.packageName !in dismissed }

    ScreenPage(
        modifier =
            Modifier
                .onPreviewKeyEvent { ev ->
                    // BACK goes through BackHandler; ESCAPE closes here. One path per key.
                    if (ev.type == KeyEventType.KeyUp && ev.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }.focusProperties {
                    // Keep focus trapped inside the overlay; BACK/ESCAPE handled above.
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                },
    ) {
        // A switcher holds a handful of cards, so they sit in the middle of the page instead of at its top.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                // The skeleton starts at the top of the page, so its last rows bleed off screen like any list.
                items == null -> {
                    PageLoading(Modifier.fillMaxSize())
                }

                visible.isEmpty() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        EmptyState("No recent apps yet")
                        Spacer(Modifier.height(ThemeSpec.Space.cozy))
                        // The only control on this page, so it is also the entry focus: ring, lift and fill land on it at once.
                        Chip(
                            androidx.compose.ui.res
                                .stringResource(com.carteltv.tv.R.string.recents_back_dismiss),
                            selected = false,
                            modifier = Modifier.entryFocus(requester = firstCard),
                            onClick = onClose,
                        )
                    }
                }

                // One switcher rail now: the row wraps its height, so the centring Box finally puts the cards
                // mid-page like the stock TV switcher, instead of a grid that filled the page from its top.
                else -> {
                    val rowState = rememberLazyListState()
                    val wrapState = rememberRailWrapState()
                    LazyRow(
                        state = rowState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = ThemeSpec.Space.md)
                                .railEdgeFade(rowState)
                                .railWrapToStart(wrapState, rowState, visible.size, firstCard)
                                .restoreRowFocus(),
                        contentPadding = PagePadding,
                        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.gutter),
                    ) {
                        itemsIndexed(visible, key = { _, app -> app.packageName }) { idx, app ->
                            RecentAppCard(
                                app = app,
                                // Keyed on the count: dismissing a card makes the next one the entry card.
                                modifier =
                                    Modifier
                                        .railWrapItem(wrapState, idx)
                                        .then(
                                            if (idx == 0) {
                                                Modifier.entryFocus(key = visible.size, requester = firstCard)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                onOpen = {
                                    AppsRepository.launchApp(ctx, app.packageName)
                                    onClose()
                                },
                                onDismissApp = {
                                    dismissed.add(app.packageName)
                                    scope.launch(Dispatchers.IO) { AppLaunchHistory.closeApp(ctx, app.packageName) }
                                    if (visible.size <= 1) {
                                        scope.launch {
                                            delay(220)
                                            onClose()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Rail width for a recent-app card: icon and label beside each other, with the caption clearing below, like the stock TV switcher. */
private val RecentCardW = 220.dp

@Composable
private fun RecentAppCard(
    app: LauncherApp,
    modifier: Modifier,
    onOpen: () -> Unit,
    onDismissApp: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val iconBmp = rememberAppIcon(app)

    Column(
        modifier =
            modifier
                .width(RecentCardW)
                .heightIn(min = 164.dp)
                .cardSurface(focused, ThemeSpec.Focus.liftCompact)
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                        onDismissApp()
                        true
                    } else {
                        false
                    }
                }.clickableNoIndication(interaction, onOpen)
                .padding(ThemeSpec.Space.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconBmp != null) {
                val iconShape = RoundedCornerShape(ThemeSpec.Card.corner)
                Image(
                    bitmap = iconBmp,
                    contentDescription = app.label,
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(iconShape)
                            .border(1.dp, if (focused) ThemeSemantics.accent else ThemeSemantics.hairline, iconShape),
                )
                Spacer(Modifier.width(ThemeSpec.Space.cozy))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontSize = ThemeSpec.Type.body,
                    fontWeight = FontWeight.SemiBold,
                    color = ThemeSemantics.textEmphatic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The card's ring and lift already say "focused"; the caption keeps one quiet colour in both states,
        // with a live accent dot marking a running app that is not currently focused.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!focused) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(ThemeSemantics.accent))
                Spacer(Modifier.width(ThemeSpec.Space.xs))
            }
            Text(
                if (focused) "OK resumes. Up closes." else "RUNNING",
                fontSize = ThemeSpec.Type.label,
                color = if (focused) ThemeSemantics.textSecondary else ThemeSemantics.accent,
                fontFamily = ThemeSpec.Font.broadcast,
                letterSpacing = 0.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
