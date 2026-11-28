package com.carteltv.tv.ui.components

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.ui.nav.ShellChrome
import com.carteltv.tv.ui.nav.cardSurface
import com.carteltv.tv.ui.theme.controlSurface
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.glassPane

/*
 * The one dialog for the whole app: menus, confirmations, text entry, multi-choice and notices.
 * It replaces the phone-style AlertDialog, which is hard to drive with a D-pad. Callers describe the
 * dialog; [TvDialogHost], mounted once above the screens, draws it with a focus trap, a scrim,
 * Back to cancel and the first sensible control focused.
 */

sealed interface TvDialogSpec {
    val title: String
    val message: String?
    val onCancel: (() -> Unit)?
}

class TvMenuSpec(
    override val title: String,
    override val message: String?,
    val items: List<String>,
    val cancelLabel: String,
    override val onCancel: (() -> Unit)?,
    val onPick: (Int) -> Unit,
) : TvDialogSpec

class TvConfirmSpec(
    override val title: String,
    override val message: String?,
    val confirmLabel: String,
    val cancelLabel: String,
    val destructive: Boolean,
    override val onCancel: (() -> Unit)?,
    val onConfirm: () -> Unit,
) : TvDialogSpec

class TvInputSpec(
    override val title: String,
    override val message: String?,
    val initial: String,
    val hint: String,
    val confirmLabel: String,
    val cancelLabel: String,
    val extraLabel: String?,
    val onExtra: (() -> Unit)?,
    override val onCancel: (() -> Unit)?,
    val onSubmit: (String) -> Unit,
) : TvDialogSpec

class TvChoiceSpec(
    override val title: String,
    override val message: String?,
    val items: List<String>,
    val selected: Set<Int>,
    val confirmLabel: String,
    val cancelLabel: String,
    override val onCancel: (() -> Unit)?,
    val onSave: (Set<Int>) -> Unit,
) : TvDialogSpec

class TvInfoSpec(
    override val title: String,
    override val message: String?,
    val okLabel: String,
) : TvDialogSpec {
    override val onCancel: (() -> Unit)? = null
}

object TvDialogs {
    /** The dialog on screen, or null. A new call replaces it, so one dialog can lead straight to the next. */
    var current by mutableStateOf<TvDialogSpec?>(null)
        private set

    /** Set by TvDialogHost while a selection-driven dialog is up; Activity dispatch forwards D-pad keys here. */
    var keyHandler: ((keyCode: Int) -> Boolean)? = null

    fun dispatchKey(keyCode: Int): Boolean = keyHandler?.invoke(keyCode) ?: false

    fun menu(
        title: String,
        items: List<String>,
        message: String? = null,
        cancelLabel: String = "Cancel",
        onCancel: (() -> Unit)? = null,
        onPick: (Int) -> Unit,
    ) {
        current = TvMenuSpec(title, message, items, cancelLabel, onCancel, onPick)
    }

    fun confirm(
        title: String,
        message: String? = null,
        confirmLabel: String = "OK",
        destructive: Boolean = false,
        cancelLabel: String = "Cancel",
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit,
    ) {
        current = TvConfirmSpec(title, message, confirmLabel, cancelLabel, destructive, onCancel, onConfirm)
    }

    fun input(
        title: String,
        initial: String = "",
        hint: String = "",
        message: String? = null,
        confirmLabel: String = "Save",
        cancelLabel: String = "Cancel",
        extraLabel: String? = null,
        onExtra: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onSubmit: (String) -> Unit,
    ) {
        current = TvInputSpec(title, message, initial, hint, confirmLabel, cancelLabel, extraLabel, onExtra, onCancel, onSubmit)
    }

    fun multiChoice(
        title: String,
        items: List<String>,
        selected: Set<Int>,
        message: String? = null,
        confirmLabel: String = "Save",
        cancelLabel: String = "Cancel",
        onCancel: (() -> Unit)? = null,
        onSave: (Set<Int>) -> Unit,
    ) {
        current = TvChoiceSpec(title, message, items, selected, confirmLabel, cancelLabel, onCancel, onSave)
    }

    fun info(
        title: String,
        message: String?,
        okLabel: String = "OK",
    ) {
        current = TvInfoSpec(title, message, okLabel)
    }

    /** Closes the dialog without running a callback. Callers use it before they run their own. */
    internal fun close() {
        current = null
    }

    /** Back or a click on the scrim: close, then let the dialog say what cancelling means. */
    fun cancel() {
        val spec = current ?: return
        current = null
        spec.onCancel?.invoke()
    }
}

@Composable
fun TvDialogHost() {
    val spec = TvDialogs.current ?: return
    BackHandler(enabled = true) { TvDialogs.cancel() }
    key(spec) {
        // Selection-driven, not focus-driven: TV D-pad keys are forwarded by MainActivity, so the
        // dialog never depends on Compose focus landing inside the modal (unreliable on-device).
        val rows =
            when (spec) {
                is TvMenuSpec -> spec.items.size + 1
                is TvConfirmSpec -> 2
                is TvInfoSpec -> 1
                else -> 0
            }
        var sel by remember(spec) {
            val start = if (spec is TvConfirmSpec && spec.destructive) 1 else 0
            mutableIntStateOf(start)
        }
        DisposableEffect(spec) {
            TvDialogs.keyHandler = { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (rows > 0) {
                            sel = (sel + 1) % rows
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (rows > 0) {
                            sel = (sel + rows - 1) % rows
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        when (spec) {
                            is TvMenuSpec -> {
                                if (sel < spec.items.size) {
                                    TvDialogs.close()
                                    spec.onPick(sel)
                                } else {
                                    TvDialogs.cancel()
                                }
                            }

                            is TvConfirmSpec -> {
                                if (sel == 0) {
                                    TvDialogs.close()
                                    spec.onConfirm()
                                } else {
                                    TvDialogs.cancel()
                                }
                            }

                            is TvInfoSpec -> {
                                TvDialogs.close()
                            }

                            else -> {
                                false
                            }
                        }
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
            onDispose { TvDialogs.keyHandler = null }
        }
        Box(
            Modifier.fillMaxSize().background(ThemeSemantics.scrimModal).dismissScrim { TvDialogs.cancel() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .swallowTaps()
                    .trapFocus()
                    .widthIn(max = 680.dp)
                    .glassPane(RoundedCornerShape(ThemeSpec.Card.cornerLg))
                    .padding(ThemeSpec.Space.lg),
                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) {
                Text(
                    spec.title,
                    fontSize = ThemeSpec.Type.title,
                    fontWeight = FontWeight.Bold,
                    color = ThemeSemantics.textEmphatic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                spec.message?.let {
                    Text(
                        it,
                        fontSize = ThemeSpec.Type.meta,
                        color = ThemeSemantics.textSecondary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (spec) {
                    is TvMenuSpec -> MenuBody(spec, sel)
                    is TvConfirmSpec -> ConfirmBody(spec, sel)
                    is TvInputSpec -> InputBody(spec)
                    is TvChoiceSpec -> ChoiceBody(spec)
                    is TvInfoSpec -> InfoBody(spec)
                }
            }
        }
    }
}

@Composable
private fun MenuBody(
    spec: TvMenuSpec,
    sel: Int,
) {
    LazyVerticalGrid(
        GridCells.Fixed(1),
        Modifier.heightIn(max = 400.dp),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
    ) {
        itemsIndexed(spec.items) { i, label ->
            DialogRow(label, selected = i == sel) {
                TvDialogs.close()
                spec.onPick(i)
            }
        }
    }
    DialogAction(spec.cancelLabel, Emphasis.Quiet, selected = sel == spec.items.size) { TvDialogs.cancel() }
}

@Composable
private fun ConfirmBody(
    spec: TvConfirmSpec,
    sel: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
        DialogAction(
            spec.confirmLabel,
            if (spec.destructive) Emphasis.Danger else Emphasis.Primary,
            selected = sel == 0,
        ) {
            TvDialogs.close()
            spec.onConfirm()
        }
        DialogAction(spec.cancelLabel, Emphasis.Quiet, selected = sel == 1) { TvDialogs.cancel() }
    }
}

@Composable
private fun InfoBody(spec: TvInfoSpec) {
    DialogAction(spec.okLabel, Emphasis.Primary, selected = true) { TvDialogs.close() }
}

/**
 * The one TV text field: [ThemeSpec.Card.corner] radius, surface2 at rest lifting to surface4 on focus,
 * accent caret, tertiary hint. Every single-line entry in the house (dialog inputs, provider keys,
 * tokens, pairing codes) routes through here so fields share one focus treatment.
 */
@Composable
fun TvTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    requester: FocusRequester? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    phoneLabel: String? = hint,
) {
    var focused by remember { mutableStateOf(false) }
    val self = remember { Any() }
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val keyboardOnFocus = keyboardOptions.showKeyboardOnFocus != false
    // OK opens the keyboard on its key-up, but only for a press that began here: the OK that opened the page
    // (pressed on a bar title) ends on this field and must not pop it.
    var okDownHere by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { PhoneTyping.leave(self) } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textEmphatic),
        cursorBrush = SolidColor(ThemeSemantics.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty() && hint.isNotEmpty()) {
                    Text(hint, fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textTertiary, maxLines = 1)
                }
                inner()
            }
        },
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                // A single-line field swallows Up/Down to move its caret, which traps the remote once the
                // keyboard is closed. They leave the field instead; Up past the top of a page reaches the bar.
                .onPreviewKeyEvent { ev ->
                    val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                    if (ok && ev.type == KeyEventType.KeyUp) {
                        if (okDownHere) keyboard?.show()
                        okDownHere = false
                        return@onPreviewKeyEvent true
                    }
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            okDownHere = true
                            true
                        }
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> {
                            // Entering the bar lands on the current section, never whichever title sits geometrically above.
                            focusManager.moveFocus(FocusDirection.Up)
                            if ((focused || ShellChrome.barFocused) && TvDialogs.current == null) ShellChrome.barFocus.tryFocus()
                            true
                        }
                        else -> false
                    }
                }.onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused && !keyboardOnFocus) keyboard?.hide()
                    if (phoneLabel == null) return@onFocusChanged
                    if (it.isFocused) PhoneTyping.enter(self, phoneLabel.ifBlank { "this field" }) else PhoneTyping.leave(self)
                }.clip(shape)
                .background(if (focused) ThemeSemantics.surfaceFocus else ThemeSemantics.surfaceRaised)
                .focusRing(focused, shape, idle = ThemeSemantics.hairline)
                .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
    )
}

@Composable
private fun InputBody(spec: TvInputSpec) {
    var text by remember { mutableStateOf(TextFieldValue(spec.initial, TextRange(spec.initial.length))) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.awaitFocus() }
    val submit = {
        TvDialogs.close()
        spec.onSubmit(text.text)
    }
    TvTextField(
        value = text,
        onValueChange = { text = it },
        hint = spec.hint,
        phoneLabel = spec.title,
        keyboardActions = KeyboardActions(onDone = { submit() }),
        requester = field,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
        DialogAction(spec.confirmLabel, Emphasis.Primary) { submit() }
        DialogAction(spec.cancelLabel, Emphasis.Quiet) { TvDialogs.cancel() }
        if (spec.extraLabel != null) {
            DialogAction(spec.extraLabel, Emphasis.Quiet) {
                TvDialogs.close()
                spec.onExtra?.invoke()
            }
        }
    }
}

@Composable
private fun ChoiceBody(spec: TvChoiceSpec) {
    val picked = remember { mutableStateListOf<Boolean>().apply { spec.items.indices.forEach { add(it in spec.selected) } } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.awaitFocus() }
    LazyVerticalGrid(
        GridCells.Fixed(1),
        Modifier.heightIn(max = 400.dp),
        verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xs),
    ) {
        itemsIndexed(spec.items) { i, label ->
            DialogRow(
                label,
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                leading = if (picked[i]) "\u2713" else "",
            ) { picked[i] = !picked[i] }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy)) {
        DialogAction(spec.confirmLabel, Emphasis.Primary) {
            TvDialogs.close()
            spec.onSave(picked.indices.filter { picked[it] }.toSet())
        }
        DialogAction(spec.cancelLabel, Emphasis.Quiet) { TvDialogs.cancel() }
    }
}

@Composable
private fun DialogRow(
    label: String,
    modifier: Modifier = Modifier,
    leading: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val hot = focused || selected
    Row(
        modifier
            .fillMaxWidth()
            .cardSurface(hot)
            .onFocusChanged { focused = it.isFocused }
            .clickableNoIndication(onClick = onClick)
            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(leading, fontSize = ThemeSpec.Type.body, color = ThemeSemantics.accent, modifier = Modifier.width(28.dp), maxLines = 1)
        }
        Text(
            label,
            fontSize = ThemeSpec.Type.body,
            fontWeight = if (hot) FontWeight.SemiBold else FontWeight.Medium,
            color = if (hot) ThemeSemantics.textEmphatic else ThemeSemantics.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class Emphasis { Primary, Quiet, Danger }

@Composable
private fun DialogAction(
    label: String,
    emphasis: Emphasis,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val primary = emphasis == Emphasis.Primary
    Text(
        label,
        fontSize = ThemeSpec.Type.meta,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color =
            if (emphasis == Emphasis.Danger && !focused) {
                ThemeSemantics.danger
            } else {
                ThemeSemantics.controlInk(focused, primary || selected)
            },
        modifier =
            modifier
                .widthIn(max = 320.dp)
                .controlSurface(focused, primary || selected)
                .onFocusChanged { focused = it.isFocused }
                .clickableNoIndication(onClick = onClick)
                .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.cozy),
    )
}
