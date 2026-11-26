package com.carteltv.tv.ui.settings

import com.carteltv.tv.ui.components.TransientStatus
import com.carteltv.tv.nav.AppLauncher
import com.carteltv.tv.core.suspendRunCatching

import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.ThemeSemantics
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.update.ReleaseUpdater
import com.carteltv.tv.data.youtube.YouTubeCategoryPresets
import com.carteltv.tv.engine.stability.NotificationMirror
import com.carteltv.tv.data.trakt.TraktAuth
import com.carteltv.tv.data.xtream.ProviderSession
import com.carteltv.tv.nav.Nav
import com.carteltv.tv.system.SleepTimerState
import com.carteltv.tv.ui.theme.*
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.AutoSizeText
import com.carteltv.tv.ui.components.TvDialogs
import com.carteltv.tv.ui.components.TvTextField
import com.carteltv.tv.ui.components.OverlayCoordinator
import com.carteltv.tv.data.events.Holidays
import com.carteltv.tv.ui.home.NowOn
import com.carteltv.tv.ui.nav.ScreenMasthead
import com.carteltv.tv.ui.components.awaitFocus
import com.carteltv.tv.ui.components.clickableNoIndication
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.restoreRowFocus
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.components.glassRow
import com.carteltv.tv.ui.components.glassRowDim
import com.carteltv.tv.ui.components.glassRowText
internal fun assistantPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Assistant")
    scope.item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var held by remember { mutableStateOf(com.carteltv.tv.voice.AssistantRole.isAssistant(ctx)) }
        var note by remember { mutableStateOf("") }

        Section("Assistant")
        ValueRow(
            title = if (held) "Cartel is the TV assistant" else "Use the remote mic button with Cartel",
            desc = "The mic button on the Shield remote opens Cartel's voice overlay instead of Google Assistant. Cartel can open any app or channel, tune live TV, control playback and volume, set sleep timers and run chained commands such as “open youtube and volume down”.",
            value = "", onClick = {},
        )
        ValueRow(
            title = if (held) "Set up the assistant again" else "Set Cartel as the assistant",
            desc = if (held) "Cartel is already the assistant. Select to confirm it again." else note.ifBlank { "Shows the system confirmation dialog." },
            value = "", onClick = {
                val activity = ctx as? android.app.Activity
                note = if (activity != null) com.carteltv.tv.voice.AssistantRole.claim(activity) else "This option is not available here."
                held = com.carteltv.tv.voice.AssistantRole.isAssistant(ctx)
            },
        )
        Caption("Works with the mic button, the phone companion, carteltv://say links and the voice command “Hey Google, search CartelTV for …”.")

        androidx.compose.runtime.LaunchedEffect(Unit) { com.carteltv.tv.voice.VoskModelManager.refresh(ctx) }
        val modelState = com.carteltv.tv.voice.VoskModelManager.state
        val modelProgress = com.carteltv.tv.voice.VoskModelManager.progress
        val modelDetail = com.carteltv.tv.voice.VoskModelManager.detail

        Section("Offline voice recognition")
        Caption("Fully free local speech-to-text (Vosk, Apache 2.0). When installed, voice commands work offline with no API key.")
        ValueRow(
            title = when (modelState) {
                com.carteltv.tv.voice.VoskModelManager.State.READY -> com.carteltv.tv.voice.VoskModelManager.INSTALLED_DETAIL
                com.carteltv.tv.voice.VoskModelManager.State.DOWNLOADING -> "Downloading offline voice model"
                com.carteltv.tv.voice.VoskModelManager.State.ERROR -> "Offline voice model error"
                com.carteltv.tv.voice.VoskModelManager.State.NOT_INSTALLED -> "Download offline voice model"
            },
            desc = when (modelState) {
                com.carteltv.tv.voice.VoskModelManager.State.DOWNLOADING -> "${(modelProgress * 100).toInt()}% · ${modelDetail.ifBlank { "Downloading ${com.carteltv.tv.voice.VoskModelManager.MODEL_NAME}" }}"
                com.carteltv.tv.voice.VoskModelManager.State.READY -> modelDetail.ifBlank { "English model (${com.carteltv.tv.voice.VoskModelManager.MODEL_NAME})" }
                com.carteltv.tv.voice.VoskModelManager.State.ERROR -> modelDetail.ifBlank { "Download failed" }
                com.carteltv.tv.voice.VoskModelManager.State.NOT_INSTALLED -> "Downloads ${com.carteltv.tv.voice.VoskModelManager.MODEL_NAME} (~40 MB) from alphacephei.com."
            },
            value = when (modelState) {
                com.carteltv.tv.voice.VoskModelManager.State.READY -> "Installed"
                com.carteltv.tv.voice.VoskModelManager.State.DOWNLOADING -> "${(modelProgress * 100).toInt()}%"
                com.carteltv.tv.voice.VoskModelManager.State.ERROR -> "Retry"
                com.carteltv.tv.voice.VoskModelManager.State.NOT_INSTALLED -> "Download"
            },
            onClick = {
                when (modelState) {
                    com.carteltv.tv.voice.VoskModelManager.State.DOWNLOADING -> com.carteltv.tv.voice.VoskModelManager.cancelDownload(ctx)
                    com.carteltv.tv.voice.VoskModelManager.State.READY -> com.carteltv.tv.voice.VoskModelManager.remove(ctx)
                    com.carteltv.tv.voice.VoskModelManager.State.ERROR,
                    com.carteltv.tv.voice.VoskModelManager.State.NOT_INSTALLED -> com.carteltv.tv.voice.VoskModelManager.download(ctx)
                }
            },
        )
        if (modelState == com.carteltv.tv.voice.VoskModelManager.State.DOWNLOADING) {
            Caption("Select to cancel download.")
        } else if (modelState == com.carteltv.tv.voice.VoskModelManager.State.READY) {
            Caption("Select to remove the offline model and fall back to the system speech recognizer.")
        }
    }
}

internal fun profilesPage(scope: LazyListScope, focus: FocusRequester) {
    pageHead(scope, "Profiles")
    scope.item {
        val faces = com.carteltv.tv.data.preferences.Profiles.state.value
        var askOnBoot by remember { mutableStateOf(AppPreferences.askProfileOnBoot) }

        Section("Profiles")
        if (faces.isEmpty()) {
            Caption("No profiles yet.")
        }
        faces.forEach { p ->
            val isActive = p.id == com.carteltv.tv.data.preferences.Profiles.activeId
            ValueRow(
                title = "${p.name}${if (isActive) " · ACTIVE" else ""}",
                desc = listOfNotNull(
                    if (p.kids) "RESTRICTED" else null,
                    if (p.dailyMinutes > 0) "${p.dailyMinutes} min per day" else null,
                    if (p.bedtimeStartMin >= 0) "Bedtime %02d:%02d".format(
                        p.bedtimeStartMin / 60, p.bedtimeStartMin % 60) else null,
                    if (p.pinSha.isNotBlank()) "LOCKED" else null,
                ).joinToString(" · ").ifBlank { "No restrictions" },
                value = if (isActive) "ACTIVE" else "Switch", focus = null,
            ) { if (!isActive) com.carteltv.tv.data.preferences.Profiles.switch(p.id) }
        }
        ValueRow(
            title = "Add profile",
            desc = "Create a new profile",
            value = "Add", focus = focus,
        ) {
            val n = faces.size + 1
            com.carteltv.tv.data.preferences.Profiles.add(
                "Profile $n",
                com.carteltv.tv.data.preferences.Profiles.FACES[n % com.carteltv.tv.data.preferences.Profiles.FACES.size],
                kids = false,
            )
        }

        Section("Startup")
        ToggleRow(
            title = "Prompt at startup",
            desc = "Show the profile picker when the app starts",
            value = askOnBoot, focus = null,
        ) {
            askOnBoot = !askOnBoot
            AppPreferences.askProfileOnBoot = askOnBoot
        }

        Section("Profile rules")
        faces.forEach { p ->
            Caption(p.name)
            val cur = com.carteltv.tv.data.preferences.Profiles.byId(p.id) ?: return@forEach

            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 34.dp, bottom = ThemeSpec.Space.xs)) {
                com.carteltv.tv.data.preferences.Profiles.FACES.take(6).forEachIndexed { idx, e ->
                    val sel = cur.emoji == e
                    var focused by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(ThemeSpec.Card.cornerSm)
                    Box(
                        modifier = Modifier
                            .padding(end = ThemeSpec.Space.xs)
                            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftIcon))
                            .clip(shape)
                            .background(
                                when {
                                    sel -> ThemeSemantics.accent.copy(alpha = 0.24f)
                                    focused -> ThemeSemantics.surfaceFocus
                                    else -> ThemeSemantics.surfaceRest
                                },
                            )
                            .glassLight()
                            .focusRing(focused, shape, idle = if (sel) ThemeSemantics.accent else ThemeSemantics.hairline)
                            .onFocusChanged { focused = it.isFocused }
                            .clickableNoIndication {
                                com.carteltv.tv.data.preferences.Profiles.update(cur.copy(emoji = e))
                            }
                            .padding(horizontal = ThemeSpec.Space.sm, vertical = ThemeSpec.Space.xs),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (idx + 1).toString(),
                            fontSize = ThemeSpec.Type.meta,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = ThemeSpec.Font.broadcast,
                            color = if (sel || focused) ThemeSemantics.textEmphatic else ThemeSemantics.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
            ValueRow(
                title = "Rename profile", desc = cur.name, value = "Rename", focus = null,
            ) {
                TvDialogs.input("Rename profile", initial = cur.name) { value ->
                    val n = value.trim()
                    if (n.isNotEmpty()) com.carteltv.tv.data.preferences.Profiles.update(cur.copy(name = n))
                }
            }
            ToggleRow(
                title = "Restricted profile",
                desc = "Applies a daily time limit and a bedtime",
                value = cur.kids, focus = null,
            ) { com.carteltv.tv.data.preferences.Profiles.update(cur.copy(kids = !cur.kids)) }
            if (cur.kids) {
                Caption("Daily time limit")
                listOf(0 to "Unlimited", 30 to "30 min", 60 to "60 min", 90 to "90 min", 120 to "120 min").forEach { (m, lbl) ->
                    ChoiceRow(
                        title = lbl, selected = cur.dailyMinutes == m, focus = null,
                    ) { com.carteltv.tv.data.preferences.Profiles.update(cur.copy(dailyMinutes = m)) }
                }
                Caption("Bedtime")
                listOf(-1 to "Off", 1200 to "20:00", 1260 to "21:00", 1320 to "22:00", 1380 to "23:00").forEach { (mins, lbl) ->
                    ChoiceRow(
                        title = lbl, selected = cur.bedtimeStartMin == mins, focus = null,
                    ) { com.carteltv.tv.data.preferences.Profiles.update(cur.copy(bedtimeStartMin = mins)) }
                }
            }
            ToggleRow(
                title = "Profile PIN",
                desc = if (cur.pinSha.isNotBlank()) "A PIN is required to open this profile"
                       else "No PIN is required to open this profile",
                value = cur.pinSha.isNotBlank(), focus = null,
            ) {
                if (cur.pinSha.isNotBlank()) {
                    com.carteltv.tv.data.preferences.Profiles.pinClear(cur.id)
                } else {
                    com.carteltv.tv.ui.settings.ProfilePinLatch.setId = cur.id
                }
            }
            ValueRow(
                title = "Remove profile",
                desc = "Delete this profile and its usage history",
                value = "Remove", focus = null,
            ) {
                TvDialogs.confirm(
                    "Remove ${cur.name}?",
                    message = "This deletes the profile and its usage history.",
                    confirmLabel = "Remove",
                    destructive = true,
                ) { com.carteltv.tv.data.preferences.Profiles.remove(cur.id) }
            }
        }
    }
}

