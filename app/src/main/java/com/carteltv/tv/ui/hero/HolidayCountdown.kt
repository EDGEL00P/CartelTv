package com.carteltv.tv.ui.hero

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.data.events.Holiday
import com.carteltv.tv.ui.components.RollingText
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.enterScene
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60 * SECOND_MS
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

internal data class HolidayTime(
    val days: String,
    val hours: String,
    val minutes: String,
    val seconds: String,
    val arrived: Boolean,
)

internal fun holidayTime(remainingMs: Long): HolidayTime {
    val ms = remainingMs.coerceAtLeast(0L)
    return HolidayTime(
        days = (ms / DAY_MS).toString(),
        hours = "%02d".format(ms % DAY_MS / HOUR_MS),
        minutes = "%02d".format(ms % HOUR_MS / MINUTE_MS),
        seconds = "%02d".format(ms % MINUTE_MS / SECOND_MS),
        arrived = remainingMs <= 0L,
    )
}

/** A fresh holiday billboard: the date it is counting toward, the holiday's name, and a live four-cell clock. */
@Composable
internal fun HolidayCountdownSlide(holiday: Holiday, inset: Dp, top: Dp) {
    val startMs = holiday.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val time = holidayTime(startMs - rememberTickingNowMs())
    val date = holiday.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]))

    Box(Modifier.fillMaxSize()) {
        HolidayBackdrop(holiday.kind)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = inset + ThemeSpec.Space.xl, top = top, end = ThemeSpec.Space.xl, bottom = 40.dp)
                .width(620.dp),
            verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy),
        ) {
            Text(
                if (time.arrived) "TODAY · $date" else "NEXT UP · $date",
                modifier = Modifier.enterScene(),
                fontSize = ThemeSpec.Type.meta,
                fontWeight = FontWeight.SemiBold,
                color = ThemeSemantics.onMedia,
                letterSpacing = ThemeSpec.Type.trackCaps,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                holiday.name,
                modifier = Modifier.enterScene(delayMs = ThemeSpec.Motion.STAGGER),
                fontSize = ThemeSpec.Type.display,
                lineHeight = ThemeSpec.Type.display * 1.1f,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.display,
                fontStyle = FontStyle.Italic,
                letterSpacing = ThemeSpec.Type.trackHeadline,
                color = ThemeSemantics.onMedia,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (time.arrived) {
                Text(
                    "The day is here. Make it a good one.",
                    modifier = Modifier.enterScene(delayMs = ThemeSpec.Motion.STAGGER * 2),
                    fontSize = ThemeSpec.Type.titleL,
                    fontWeight = FontWeight.SemiBold,
                    color = ThemeSemantics.onMedia,
                    maxLines = 1,
                )
            } else {
                HolidayClock(time)
            }
            // The tagline only fits once the clock is gone; with it the stack climbs under the nav bar.
            if (time.arrived) {
                Text(
                    holiday.tagline,
                    fontSize = ThemeSpec.Type.body,
                    color = ThemeSemantics.onMediaMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HolidayClock(time: HolidayTime) {
    Row(
        Modifier
            .enterScene(delayMs = ThemeSpec.Motion.STAGGER * 2)
            .clip(RoundedCornerShape(ThemeSpec.Card.corner))
            .background(ThemeSemantics.onMedia.copy(alpha = 0.12f))
            .border(1.dp, ThemeSemantics.onMedia.copy(alpha = 0.22f), RoundedCornerShape(ThemeSpec.Card.corner))
            .padding(horizontal = ThemeSpec.Space.lg, vertical = ThemeSpec.Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.lg),
    ) {
        // Every cell shares one size and one baseline; the rule after the days is all that sets them apart.
        ClockUnit(time.days, if (time.days == "1") "DAY" else "DAYS")
        Box(Modifier.width(1.dp).height(36.dp).background(ThemeSemantics.onMedia.copy(alpha = 0.24f)))
        Row(
            horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClockUnit(time.hours, "HOURS")
            ClockUnit(time.minutes, "MINUTES")
            ClockUnit(time.seconds, "SECONDS")
        }
    }
}

@Composable
private fun ClockUnit(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RollingText(
            value,
            fontSize = ThemeSpec.Type.titleL,
            fontFamily = ThemeSpec.Font.mono,
            fontWeight = FontWeight.Bold,
            color = ThemeSemantics.onMedia,
        )
        ClockLabel(label)
    }
}

@Composable
private fun ClockLabel(label: String) {
    Text(
        label,
        fontSize = ThemeSpec.Type.label,
        fontWeight = FontWeight.SemiBold,
        color = ThemeSemantics.onMediaMuted,
        letterSpacing = ThemeSpec.Type.trackCaps,
        maxLines = 1,
    )
}
