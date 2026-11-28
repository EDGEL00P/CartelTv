package com.carteltv.tv.ui.youtube
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.settings.QrCode
import com.carteltv.tv.ui.theme.ThemeSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import coil3.compose.AsyncImage
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.youtube.YouTubeCategoryPresets
import com.carteltv.tv.data.youtube.YouTubeChapters
import com.carteltv.tv.data.youtube.YouTubeDataSource
import com.carteltv.tv.data.youtube.YouTubeDislikes
import com.carteltv.tv.data.youtube.YouTubeFeedFilter
import com.carteltv.tv.data.youtube.YouTubeApiClient
import com.carteltv.tv.data.youtube.YouTubeLibrary
import com.carteltv.tv.data.youtube.YouTubeSegmentPlan
import com.carteltv.tv.data.youtube.YouTubeSubscriptions
import com.carteltv.tv.engine.stability.PlaybackFailure
import com.carteltv.tv.ui.player.PlayerGlyph
import com.carteltv.tv.ui.player.PlayerGlyphIcon
import com.carteltv.tv.ui.player.PlayerControlState
import com.carteltv.tv.ui.player.DialogueBoost
import com.carteltv.tv.ui.player.PlayerErrorCard
import com.carteltv.tv.ui.player.PlayerChrome
import com.carteltv.tv.ui.player.PlayerHandover
import com.carteltv.tv.ui.player.PlayerMeta
import com.carteltv.tv.ui.player.PlayerOverlay
import com.carteltv.tv.ui.player.PlayerRemote
import com.carteltv.tv.ui.player.PlayerVideoView
import com.carteltv.tv.ui.player.PlaybackStallGuard
import com.carteltv.tv.ui.player.ReportNowPlaying
import com.carteltv.tv.ui.player.UpNextCard
import com.carteltv.tv.ui.player.buildCartelPlayer
import com.carteltv.tv.ui.player.cartelExtractors
import com.carteltv.tv.ui.player.playerSurface
import com.carteltv.tv.ui.player.rememberPlayerRemote
import com.carteltv.tv.ui.player.rememberVideoGeometry
import com.carteltv.tv.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.home.ShelfEntry
import com.carteltv.tv.ui.components.CardArtwork
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.clickableTv
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.trapFocus

/* CARTEL · YOUTUBE chrome — floating panels, masthead extras and loading
   feedback for the mirror of YouTube's TV app; the screen-level banner lives
   in YouTubeScreen.kt. */

@Composable
internal fun YouTubeConceptPanels(
    visible: Boolean,
    title: String,
    channel: String?,
    channelAvatar: String?,
    publishedMs: Long,
    publishedAt: Long = 0L,
    viewCount: String?,
    isLive: Boolean,
    votes: YouTubeDislikes.Votes?,
    next: YouTubeApiClient.YouTubeVideo?,
    related: List<YouTubeApiClient.YouTubeVideo>,
    onSelectRelated: (YouTubeApiClient.YouTubeVideo) -> Unit,
) {
    if (!visible) return
    val age = remember(publishedMs, publishedAt) {
        YouTubeSubscriptions.uploadedAt(if (publishedAt > 0L) publishedAt else publishedMs)
            .takeIf { it.isNotEmpty() }?.let { "Uploaded $it" }
            ?: YouTubeSubscriptions.ago(publishedMs)
    }
    val viewText = remember(viewCount) {
        val n = viewCount?.toLongOrNull()
        when {
            n != null -> "%,d views".format(n)
            !viewCount.isNullOrBlank() -> viewCount
            else -> null
        }
    }
    Column(
        Modifier.padding(start = ThemeSpec.Safe.horizontal, top = ThemeSpec.Safe.vertical).widthIn(max = 560.dp)
            .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
            .background(PlayerChrome.chip)
            .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg))
            .padding(ThemeSpec.Space.md),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(ThemeSemantics.surfaceRaised), contentAlignment = Alignment.Center) {
                if (channelAvatar != null) {
                    CardArtwork(channelAvatar, channel ?: "channel", Modifier.fillMaxSize())
                } else {
                    Text((channel?.firstOrNull()?.uppercaseChar() ?: 'Y').toString(), fontSize = ThemeSpec.Type.micro, color = ThemeSemantics.textEmphatic, fontWeight = FontWeight.Bold)
                }
            }
            Text(channel ?: "YouTube", fontSize = ThemeSpec.Type.meta, color = PlayerChrome.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Box(Modifier.clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(YouTubeAccent).padding(horizontal = ThemeSpec.Space.md, vertical = 3.dp)) {
                Text("Subscribe", fontSize = ThemeSpec.Type.micro, color = ThemeSemantics.textInverse, fontWeight = FontWeight.Bold)
            }
        }
        Text(title, fontSize = ThemeSpec.Type.titleL, color = PlayerChrome.ink, fontWeight = FontWeight.Bold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        val metaLine = listOfNotNull(if (isLive) "Live" else null, viewText, age.takeIf { it.isNotBlank() }).joinToString(" · ")
        Text(metaLine.ifBlank { "Date unavailable" }, fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.md)) {
            Text("Like: ${votes?.likes?.let { "%,d".format(it) } ?: "—"}", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted)
            Text("Dislike: ${votes?.dislikes?.let { "%,d".format(it) } ?: "—"}", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted)
        }
    }

    if (related.isNotEmpty() || next != null) {
        val items = listOfNotNull(next) + related.filter { it.videoId != next?.videoId }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
            Column(Modifier.padding(start = ThemeSpec.Safe.horizontal, bottom = 112.dp, end = 260.dp)) {
                Text("Related / Up Next", fontSize = ThemeSpec.Type.meta, color = PlayerChrome.ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(ThemeSpec.Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    items.take(3).forEach { v ->
                        val interaction = remember { MutableInteractionSource() }
                        val focused by interaction.collectIsFocusedAsState()
                        Column(
                            Modifier.width(100.dp)
                                .clip(RoundedCornerShape(ThemeSpec.Card.cornerSm))
                                .border(ThemeSpec.Focus.ringWidth, if (focused) PlayerChrome.ink else Color.Transparent, RoundedCornerShape(ThemeSpec.Card.cornerSm))
                                .clickableNoIndication(interaction) { onSelectRelated(v) }
                        ) {
                            Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(ThemeSpec.Card.cornerSm)).background(ThemeSemantics.surfaceRest)) {
                                CardArtwork(v.thumb, v.title, Modifier.fillMaxSize())
                                v.duration?.let { d ->
                                    Text(d, fontSize = ThemeSpec.Type.micro, color = ThemeSemantics.onMedia, modifier = Modifier.align(Alignment.BottomEnd).padding(ThemeSpec.Space.xs).background(ThemeSemantics.scrimChip, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp))
                                }
                            }
                            Text(v.title, fontSize = ThemeSpec.Type.micro, color = PlayerChrome.ink, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(v.channel ?: "", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun YouTubeCommentsPanel(comments: List<YouTubeApiClient.YouTubeComment>, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickableTv(hoverEffect = false, showHand = false) { onDismiss() })
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = ThemeSpec.Safe.vertical, end = ThemeSpec.Safe.horizontal, bottom = 96.dp).width(200.dp)
                .clip(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .background(PlayerChrome.chip)
                .border(1.dp, ThemeSemantics.hairline, RoundedCornerShape(ThemeSpec.Card.cornerLg))
                .padding(ThemeSpec.Space.md),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
        ) {
            Text("Comments", fontSize = ThemeSpec.Type.body, color = PlayerChrome.ink, fontWeight = FontWeight.Bold)
            Text("Sort: Top / Newest", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted)
            if (comments.isEmpty()) {
                Text("No comments available (best-effort InnerTube fetch returned none).", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    comments.forEach { c ->
                        Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.author, fontSize = ThemeSpec.Type.micro, color = PlayerChrome.ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                c.time?.let { Text(it, fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted, maxLines = 1) }
                            }
                            Text(c.text, fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            c.likes?.let { Text("Likes: $it", fontSize = ThemeSpec.Type.micro, color = PlayerChrome.inkMuted, maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

