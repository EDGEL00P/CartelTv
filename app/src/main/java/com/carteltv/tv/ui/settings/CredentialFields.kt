package com.carteltv.tv.ui.settings
import com.carteltv.tv.ui.theme.ThemeSpec
import androidx.compose.foundation.layout.*
import androidx.tv.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carteltv.tv.ui.components.TvTextField
import com.carteltv.tv.ui.theme.ThemeSemantics

/**
 * TV text-field behaviour: focusing a field never pops the on-screen keyboard over the form
 * (the phone companion can type instead); OK on the focused field opens it on demand.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.tvKeyboard(controller: SoftwareKeyboardController?, onFocus: (Boolean) -> Unit = {}): Modifier = this
    .onFocusChanged {
        onFocus(it.isFocused)
        if (it.isFocused) controller?.hide()
    }
    .onPreviewKeyEvent { ev ->
        if (ev.key == Key.DirectionCenter) {
            if (ev.type == KeyEventType.KeyUp) controller?.show()
            true
        } else false
    }

/**
 * String-backed state for the house field: [TvTextField] edits a [TextFieldValue] so the caret and the
 * keyboard's half-typed word survive, while settings code passes plain strings. The string wins whenever
 * it changes from outside the field (a Clear button, a reset); the field's own edits echo back equal.
 */
@Composable
internal fun rememberFieldValue(value: String): MutableState<TextFieldValue> {
    val field = remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.value.text != value) field.value = TextFieldValue(value, TextRange(value.length))
    return field
}

/** Sign-in fields share one measure: a portal URL fits on a single line and stays clear of the phone panel docked right. */
private val FIELD_WIDTH = 460.dp

/**
 * Reusable credential input field used across all login screens. Plain entries are the house
 * [TvTextField]; secrets go to [PasswordField], which wears the same chrome.
 */
@Composable
fun CredentialInputField(
    label: String,
    value: String,
    placeholder: String = "",
    password: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onValueChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column {
        Text(label, fontSize = ThemeSpec.Type.label, color = ThemeSemantics.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(ThemeSpec.Space.xs))
        val fieldModifier = modifier.tvKeyboard(keyboard).widthIn(max = FIELD_WIDTH)
        if (password) {
            PasswordField(value, placeholder, fieldModifier, focusRequester, onValueChange)
        } else {
            val field = rememberFieldValue(value)
            TvTextField(
                value = field.value,
                onValueChange = {
                    field.value = it
                    onValueChange(it.text)
                },
                modifier = fieldModifier,
                hint = placeholder,
                requester = focusRequester,
            )
        }
    }
}

/**
 * Compact field variant for inline settings forms (narrower, no label).
 */
@Composable
fun CompactField(
    value: String,
    placeholder: String = "",
    password: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onValueChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldModifier = modifier.tvKeyboard(keyboard)
    if (password) {
        PasswordField(value, placeholder, fieldModifier, focusRequester, onValueChange)
    } else {
        val field = rememberFieldValue(value)
        TvTextField(
            value = field.value,
            onValueChange = {
                field.value = it
                onValueChange(it.text)
            },
            modifier = fieldModifier,
            hint = placeholder,
            requester = focusRequester,
        )
    }
}

/**
 * Masked twin of [TvTextField] for passwords and client secrets: the house field with the password
 * transformation as the only difference, so secrets read as the same chrome as every other field.
 */
@Composable
private fun PasswordField(
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    requester: FocusRequester? = null,
    onValueChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val field = rememberFieldValue(value)
    TvTextField(
        value = field.value,
        onValueChange = {
            field.value = it
            onValueChange(it.text)
        },
        modifier = modifier.tvKeyboard(keyboard),
        hint = hint,
        requester = requester,
        visualTransformation = PasswordVisualTransformation(),
    )
}
