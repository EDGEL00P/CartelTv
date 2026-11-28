package com.carteltv.tv.ui.weather

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.carteltv.tv.data.preferences.AppPreferences
import com.carteltv.tv.data.weather.DeviceLocation
import com.carteltv.tv.data.weather.Place
import com.carteltv.tv.data.weather.WeatherService
import com.carteltv.tv.data.weather.WeatherUnits
import com.carteltv.tv.data.weather.WindUnit
import com.carteltv.tv.data.weather.reverseGeocode
import com.carteltv.tv.ui.catalog.ActionButton
import com.carteltv.tv.ui.components.FilterChip
import com.carteltv.tv.ui.components.combinedClickableNoIndication
import com.carteltv.tv.ui.components.dismissScrim
import com.carteltv.tv.ui.components.entryFocus
import com.carteltv.tv.ui.components.trapFocus
import com.carteltv.tv.ui.theme.ThemeSemantics
import com.carteltv.tv.ui.theme.ThemeSpec
import com.carteltv.tv.ui.theme.focusLift
import com.carteltv.tv.ui.theme.focusRing
import com.carteltv.tv.ui.theme.focusScaleState
import com.carteltv.tv.ui.theme.glassLight
import com.carteltv.tv.ui.theme.glassSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sheet content scope: [item] is a full-span row (options, fields, text), [items] are the repeated
 * place cards that sit two to a row.
 */
private class SheetGrid(
    private val grid: LazyGridScope,
) {
    fun item(content: @Composable LazyGridItemScope.() -> Unit) {
        grid.item(span = { GridItemSpan(maxLineSpan) }, content = content)
    }

    fun items(
        count: Int,
        key: ((index: Int) -> Any)? = null,
        contentType: (index: Int) -> Any? = {
            null
        },
        itemContent: @Composable LazyGridItemScope.(Int) -> Unit,
    ) {
        grid.items(count, key = key, contentType = contentType, itemContent = itemContent)
    }
}

@Composable
private fun SheetFrame(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    content: SheetGrid.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(ThemeSemantics.scrimModal).dismissScrim(onDismiss)) {
        val sheetShape = RoundedCornerShape(topStart = ThemeSpec.Card.cornerLg, bottomStart = ThemeSpec.Card.cornerLg)
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(500.dp)
                .clip(sheetShape)
                .background(ThemeSemantics.surfaceModal)
                .glassLight()
                .border(1.dp, ThemeSemantics.hairline, sheetShape)
                .trapFocus()
                .padding(start = ThemeSpec.Space.lg, end = ThemeSpec.Space.lg, top = ThemeSpec.Space.lg, bottom = ThemeSpec.Space.xl),
        ) {
            Spacer(Modifier.height(ThemeSpec.Space.lg))
            Text(
                title,
                fontSize = ThemeSpec.Type.titleL,
                fontWeight = FontWeight.Bold,
                fontFamily = ThemeSpec.Font.display,
                color = ThemeSemantics.textEmphatic,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (subtitle !=
                null
            ) {
                Text(
                    subtitle,
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.textTertiary,
                    modifier = Modifier.padding(top = ThemeSpec.Space.xs),
                )
            }
            Spacer(Modifier.height(ThemeSpec.Space.cozy))
            LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.cozy),
                horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm),
            ) { SheetGrid(this).content() }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    options: List<String>,
    selected: Int,
    first: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    Column {
        Text(
            title,
            fontSize = ThemeSpec.Type.meta,
            color = ThemeSemantics.textSecondary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(ThemeSpec.Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
            options.forEachIndexed { i, label ->
                val mod = if (first && i == selected.coerceAtLeast(0)) Modifier.entryFocus() else Modifier
                FilterChip(label, selected == i, modifier = mod) { onSelect(i) }
            }
        }
    }
}

private val onOff = listOf("On", "Off")

@Composable
fun OptionsSheet(
    radar: RadarState,
    onAmbient: (Boolean) -> Unit,
    onLightning: (Boolean) -> Unit,
    onFeed: (Boolean) -> Unit,
    onStation: () -> Unit,
    onDismiss: () -> Unit,
) {
    var rev by remember { mutableStateOf(0) }

    fun bump() {
        rev++
        WeatherUnits.changed()
    }

    SheetFrame("Options", "Changes are saved automatically", onDismiss) {
        check(rev >= 0) // read here so a pref change rebuilds the rows
        item {
            OptionRow("Temperature", listOf("°C", "°F"), if (AppPreferences.weatherFahrenheit) 1 else 0, first = true) {
                AppPreferences.weatherFahrenheit =
                    it == 1
                ; bump()
            }
        }

        item {
            OptionRow("Wind and distance", WindUnit.entries.map { it.label }, WindUnit.of(AppPreferences.weatherWindUnit).ordinal) {
                AppPreferences.weatherWindUnit = WindUnit.entries[it].name
                bump()
            }
        }
        item {
            OptionRow("Temperature detail", listOf("Whole degrees", "One decimal"), if (AppPreferences.weatherDecimals) 1 else 0) {
                AppPreferences.weatherDecimals =
                    it == 1
                ; bump()
            }
        }
        item {
            Column {
                OptionRow(
                    "Current conditions from",
                    listOf("Nearest station", "Model only"),
                    if (AppPreferences.weatherUseStation) 0 else 1,
                ) {
                    AppPreferences.weatherUseStation = it == 0
                    onStation()
                    bump()
                }
                Text(
                    "A station is an airport weather report within ${WeatherUnits.distance(
                        com.carteltv.tv.data.weather.WeatherObservations.MAX_KM,
                    )} and under ${com.carteltv.tv.data.weather.WeatherObservations.MAX_AGE_MIN} minutes old. " +
                        "Reports are whole degrees, so they correct the model rather than add decimals.",
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textTertiary,
                    modifier = Modifier.padding(top = ThemeSpec.Space.sm),
                )
            }
        }
        item {
            val mins = listOf(10, 15, 30, 60)
            OptionRow("Refresh every", mins.map { "$it min" }, mins.indexOf(AppPreferences.weatherRefreshMin).coerceAtLeast(1)) {
                AppPreferences.weatherRefreshMin =
                    mins[it]
                ; bump()
            }
        }
        item {
            OptionRow("Ambient weather effects", onOff, if (AppPreferences.weatherAmbient) 0 else 1) {
                AppPreferences.weatherAmbient =
                    it == 0
                ; onAmbient(it == 0)
                bump()
            }
        }
        item {
            Column {
                OptionRow("Live lightning feed (experimental)", onOff, if (AppPreferences.lightningFeed) 0 else 1) {
                    AppPreferences.lightningFeed =
                        it == 0
                    ; onFeed(it == 0)
                    bump()
                }
                Text(
                    "This is an unofficial Blitzortung.org community stream and a partial sample. Its operators restrict app use, so it is off by default.",
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textTertiary,
                    modifier = Modifier.padding(top = ThemeSpec.Space.sm),
                )
            }
        }
        if (AppPreferences.lightningFeed) {
            item {
                OptionRow("Show strikes on the radar", onOff, if (AppPreferences.radarLightning) 0 else 1) {
                    AppPreferences.radarLightning =
                        it == 0
                    ; onLightning(it == 0)
                    bump()
                }
            }
        }
        item {
            OptionRow("Radar autoplay", onOff, if (AppPreferences.radarAutoPlay) 0 else 1) {
                AppPreferences.radarAutoPlay = it == 0
                radar.playing =
                    it == 0
                bump()
            }
        }
        item {
            val speeds = listOf(1000, 600, 350)
            OptionRow(
                "Radar speed",
                listOf("Slow", "Normal", "Fast"),
                speeds.indexOf(AppPreferences.radarFrameMs).let {
                    if (it <
                        0
                    ) {
                        1
                    } else {
                        it
                    }
                },
            ) {
                AppPreferences.radarFrameMs = speeds[it]
                radar.frameMs = speeds[it]
                bump()
            }
        }
        item {
            val levels = listOf(50, 65, 80, 100)
            OptionRow("Radar opacity", levels.map { "$it%" }, levels.indexOf(AppPreferences.radarOpacity).let { if (it < 0) 2 else it }) {
                AppPreferences.radarOpacity = levels[it]
                radar.opacity = levels[it]
                bump()
            }
        }
        item {
            OptionRow("Default radar zoom", listOf("Wide", "Region", "Metro", "Local"), (AppPreferences.radarZoom - 4).coerceIn(0, 3)) {
                radar.zoom = it + 4
                AppPreferences.radarZoom = it + 4
                bump()
            }
        }
        item { Spacer(Modifier.height(ThemeSpec.Space.lg)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    place: Place,
    current: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(ThemeSpec.Card.corner)
    Row(
        modifier
            .focusLift(focusScaleState(focused, ThemeSpec.Focus.liftCompact, pressed))
            .fillMaxWidth()
            .glassSurface(focused, shape)
            .then(if (current && !focused) Modifier.background(ThemeSemantics.accent.copy(alpha = 0.10f)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickableNoIndication(interactionSource = interaction, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                place.name,
                fontSize = ThemeSpec.Type.body,
                color = ThemeSemantics.textEmphatic,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(place.region, place.country).filter { it.isNotBlank() && it != place.name }.joinToString(", ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (current) WeatherPill("Current", ThemeSemantics.accent)
    }
}

@Composable
private fun CoordField(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' || c == '-' || c == '−' }.replace('−', '-')) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = TextStyle(fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textEmphatic),
        cursorBrush = SolidColor(ThemeSemantics.accent),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(ThemeSemantics.surfaceRaised, RoundedCornerShape(ThemeSpec.Card.corner))
                    .border(
                        1.dp,
                        ThemeSemantics.hairline,
                        RoundedCornerShape(ThemeSpec.Card.corner),
                    ).padding(horizontal = ThemeSpec.Space.cozy, vertical = ThemeSpec.Space.cozy),
            ) {
                if (value.isEmpty()) Text(hint, fontSize = ThemeSpec.Type.meta, color = ThemeSemantics.textTertiary, maxLines = 1)
                inner()
            }
        },
        modifier = modifier,
    )
}

@Composable
fun LocationSheet(
    current: Place,
    elevationM: Double?,
    onPlace: (Place) -> Unit,
    onAutoChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var deviceStatus by remember { mutableStateOf<String?>(null) }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var coordError by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(AppPreferences.weatherAuto) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(AppPreferences.weatherPlaces) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(450)
        results = WeatherService.search(query)
        searching = false
    }

    fun choose(
        p: Place,
        manual: Boolean,
    ) {
        if (manual) {
            AppPreferences.weatherAuto = false
            auto = false
        }
        WeatherService.activate(p)
        if (manual) AppPreferences.weatherPlaces = (listOf(p) + saved.filterNot { it.sameSpot(p) }).take(8).also { saved = it }
        onPlace(p)
        onDismiss()
    }

    fun useDevice() {
        scope.launch {
            deviceStatus = "Locating…"
            val fix = DeviceLocation.fix(ctx)
            if (fix == null) {
                deviceStatus = "This TV has no location service. Use coordinates or search."
                return@launch
            }
            val p = reverseGeocode(fix.lat, fix.lon)
            deviceStatus = null
            choose(p, manual = true)
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) useDevice() else deviceStatus = "Location permission was declined."
        }

    fun useCoordinates() {
        val lat = latText.trim().toDoubleOrNull()
        val lon = lonText.trim().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            coordError = "Latitude is −90 to 90 and longitude −180 to 180, in decimal degrees."
            return
        }
        coordError = null
        scope.launch { choose(reverseGeocode(lat, lon), manual = true) }
    }

    val here = "%.4f, %.4f".format(current.lat, current.lon) + (elevationM?.let { " · ${WeatherUnits.altitude(it)} elevation" } ?: "")
    SheetFrame("Location", "${current.label.ifBlank { current.name }}\n$here", onDismiss) {
        item {
            OptionRow("Detect automatically", onOff, if (auto) 0 else 1, first = true) {
                auto = it == 0
                AppPreferences.weatherAuto = auto
                onAutoChanged()
            }
            Text(
                "Uses this device's location each time Weather opens, falling back to your network's approximate location.",
                fontSize = ThemeSpec.Type.label,
                color = ThemeSemantics.textTertiary,
                modifier = Modifier.padding(top = ThemeSpec.Space.sm),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    ActionButton("Use device location", filled = false) {
                        if (DeviceLocation.hasPermission(ctx)) useDevice() else permission.launch(DeviceLocation.PERMISSION)
                    }
                    ActionButton(detecting ?: "Detect by network", filled = false) {
                        scope.launch {
                            detecting = "Detecting…"
                            val p = WeatherService.locateAuto(ctx, force = true)
                            if (p != null) choose(p, manual = false) else detecting = "Couldn't detect. Try search."
                        }
                    }
                }
                Text(
                    deviceStatus ?: "Device location uses Wi-Fi or GPS where available, and is accurate to tens of metres.",
                    fontSize = ThemeSpec.Type.label,
                    color = ThemeSemantics.textTertiary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        item {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = ThemeSpec.Type.body, color = ThemeSemantics.textEmphatic),
                cursorBrush = SolidColor(ThemeSemantics.accent),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(ThemeSemantics.surfaceRaised, RoundedCornerShape(ThemeSpec.Card.corner))
                            .border(1.dp, ThemeSemantics.accent.copy(alpha = 0.4f), RoundedCornerShape(ThemeSpec.Card.corner))
                            .padding(horizontal = ThemeSpec.Space.md, vertical = ThemeSpec.Space.cozy),
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                "Search for a city or town",
                                fontSize = ThemeSpec.Type.body,
                                color = ThemeSemantics.textTertiary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                Text(
                    "Exact coordinates",
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.textSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ThemeSpec.Space.sm)) {
                    CoordField(latText, "Latitude  −27.4698", { latText = it }, Modifier.weight(1f))
                    CoordField(lonText, "Longitude  153.0251", { lonText = it }, Modifier.weight(1f))
                }
                ActionButton("Use these coordinates", filled = false, onClick = ::useCoordinates)
                coordError?.let {
                    Text(
                        it,
                        fontSize = ThemeSpec.Type.label,
                        color = ThemeSemantics.danger,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (query.trim().length >= 2) {
            if (results.isEmpty()) {
                item {
                    Text(
                        if (searching) "Searching…" else "No matches",
                        fontSize = ThemeSpec.Type.meta,
                        color = ThemeSemantics.textTertiary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            items(results.size, key = {
                "r_${it}_${results[it].lat}_${results[it].lon}"
            }, contentType = { "place" }) { i ->
                PlaceRow(
                    results[i],
                    current = results[i].sameSpot(current),
                ) { choose(results[i], manual = true) }
            }
        } else if (saved.isNotEmpty()) {
            item {
                Text(
                    "Saved places. Press and hold OK to remove.",
                    fontSize = ThemeSpec.Type.meta,
                    color = ThemeSemantics.textSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            items(saved.size, key = { "s_${it}_${saved[it].lat}_${saved[it].lon}" }, contentType = { "place" }) { i ->
                val p = saved[i]
                PlaceRow(p, current = p.sameSpot(current), onLongClick = {
                    saved =
                        saved.filterNot { it === p }.also { AppPreferences.weatherPlaces = it }
                }) { choose(p, manual = true) }
            }
        }
        item { Spacer(Modifier.height(ThemeSpec.Space.lg)) }
    }
}
