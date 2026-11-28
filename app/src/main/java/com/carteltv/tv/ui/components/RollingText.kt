package com.carteltv.tv.ui.components
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/** Fixed-width digits, so a ticking clock or a changing score never shifts the text beside it. */
internal const val TABULAR = "tnum"

private const val ROLL_MS = 260

private fun rises(from: Char, to: Char): Boolean = to > from || (from == '9' && to == '0')

/**
 * A number or clock whose digits roll one by one, only where they change. A digit that goes up rolls up;
 * with [animateDown] false a digit that goes down (a corrected score) just changes, with no flourish.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun RollingText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    animateDown: Boolean = true,
) {
    Row(modifier) {
        text.forEach { ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val up = rises(initialState, targetState)
                    when {
                        !initialState.isDigit() || !targetState.isDigit() -> ContentTransform(EnterTransition.None, ExitTransition.None)
                        !up && !animateDown -> ContentTransform(EnterTransition.None, ExitTransition.None)
                        else -> {
                            val dir = if (up) 1 else -1
                            (slideInVertically(tween(ROLL_MS)) { dir * it / 2 } + fadeIn(tween(ROLL_MS))) togetherWith
                                (slideOutVertically(tween(ROLL_MS)) { -dir * it / 2 } + fadeOut(tween(ROLL_MS / 2)))
                        }
                    }
                },
                label = "digit",
            ) { c ->
                Text(
                    c.toString(), fontSize = fontSize, color = color, fontWeight = fontWeight, fontFamily = fontFamily,
                    style = TextStyle(fontFeatureSettings = TABULAR), maxLines = 1, softWrap = false,
                )
            }
        }
    }
}
