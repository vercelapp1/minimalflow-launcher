package com.minimalflow.launcher.ui.launcher

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.SortMode
import com.minimalflow.launcher.core.model.TemperatureUnit
import com.minimalflow.launcher.core.model.WeatherGlyph
import com.minimalflow.launcher.core.model.WeatherSnapshot
import com.minimalflow.launcher.core.ui.Spacing
import com.minimalflow.launcher.core.ui.components.AppRow
import com.minimalflow.launcher.core.ui.components.EmptyState
import com.minimalflow.launcher.core.ui.components.IconTile
import com.minimalflow.launcher.core.ui.components.SectionHeader
import com.minimalflow.launcher.core.ui.minimalFlowColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** How an icon is fetched for a row. Injected so tests can supply a fake. */
typealias IconLoader = suspend (AppListEntry, Int) -> ImageBitmap?

/** Converts a dp size to the pixel size the icon repository expects. */
typealias PxConverter = (Dp) -> Int

/**
 * The launcher home screen: clock, optional weather, favourites, then the list.
 *
 * One `LazyColumn` rather than a `Column` of lists, so the whole thing scrolls as
 * a single surface and only the rows on screen are composed. That is the most
 * important performance decision in the app: an eager list of a few hundred rows
 * would allocate every icon slot up front and make the first frame miss.
 */
@Composable
fun HomeScreen(
    viewModel: LauncherViewModel,
    loadIcon: IconLoader,
    pxFor: PxConverter,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Keyed on the message id so two identical texts in a row both show.
    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.text)
        viewModel.consumeMessage()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Spacing.xl, bottom = Spacing.xxl),
        ) {
            item(key = "header") {
                HomeHeader(
                    configuration = configuration,
                    weather = weather,
                    onSettings = onOpenSettings,
                    onRefreshWeather = viewModel::refreshWeather,
                )
            }

            if (configuration.showFavorites && favorites.isNotEmpty()) {
                item(key = "favourites") {
                    FavouritesStrip(
                        entries = favorites,
                        configuration = configuration,
                        loadIcon = loadIcon,
                        pxFor = pxFor,
                        onClick = viewModel::launch,
                        onLongClick = { viewModel.showAppMenu(it.key) },
                    )
                }
            }

            if (sections.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "No apps to show",
                        detail = "Anything you install will appear here.",
                    )
                }
            }

            sections.forEach { section ->
                if (section.letter.isNotEmpty()) {
                    item(key = "section-${section.letter}") {
                        SectionHeader(letter = section.letter)
                    }
                }
                items(items = section.entries, key = { it.key.storageKey() }) { entry ->
                    AppRow(
                        entry = entry,
                        iconSize = configuration.iconSize.dp,
                        rowHeight = configuration.rowHeight.dp,
                        showIcon = configuration.showIcons,
                        showLabel = configuration.showLabels,
                        loadIcon = loadIcon,
                        pxFor = pxFor,
                        onClick = { viewModel.launch(entry) },
                        onLongClick = { viewModel.showAppMenu(entry.key) },
                    )
                }
            }

            item(key = "footer") {
                SortFooter(
                    mode = configuration.sortingMode,
                    onClick = viewModel::showSortPicker,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.lg),
        )
    }

    HomeSheets(
        sheet = sheet,
        sections = sections,
        configuration = configuration,
        loadIcon = loadIcon,
        pxFor = pxFor,
        onDismiss = viewModel::dismissSheet,
        onLaunch = viewModel::launch,
        onToggleFavorite = viewModel::toggleFavorite,
        onHide = viewModel::hideApp,
        onAppInfo = viewModel::openAppInfo,
        onUninstall = viewModel::requestUninstall,
        onSortMode = viewModel::setSortMode,
    )
}

/** Clock, date, greeting and the weather line. */
@Composable
private fun HomeHeader(
    configuration: LauncherConfiguration,
    weather: WeatherSnapshot?,
    onSettings: () -> Unit,
    onRefreshWeather: () -> Unit,
) {
    val colors = minimalFlowColors()
    val clock = rememberClock()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            if (configuration.showClock) {
                Text(
                    text = clock.time,
                    color = colors.primaryText,
                    fontSize = configuration.typography.clockSizeSp.sp,
                    fontWeight = FontWeight.Light,
                )
            } else {
                Spacer(Modifier.width(Spacing.none))
            }
            CircleButton(label = "Settings", onClick = onSettings)
        }

        if (configuration.showDate) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = clock.date,
                color = colors.secondaryText,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (configuration.showGreeting) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = greeting(),
                color = colors.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        AnimatedVisibility(
            visible = configuration.showWeather && weather != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val snapshot = weather
            if (snapshot != null) {
                WeatherLine(
                    snapshot = snapshot,
                    unit = configuration.weather.unit,
                    onClick = onRefreshWeather,
                )
            }
        }
    }
}

/**
 * The weather line, always with its age.
 *
 * A temperature with no timestamp invites the reader to treat a reading from
 * three hours ago as current, which is exactly the quiet inaccuracy a weather
 * module should not have.
 */
@Composable
private fun WeatherLine(
    snapshot: WeatherSnapshot,
    unit: TemperatureUnit,
    onClick: () -> Unit,
) {
    val colors = minimalFlowColors()
    val temperature = snapshot.temperature(unit)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${glyphSymbol(snapshot.weatherCode.glyph)}  " +
                "${temperature.toInt()}\u00B0${unit.symbol.drop(1)}",
            color = colors.primaryText,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = weatherCaption(snapshot, unit),
            color = colors.secondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun weatherCaption(snapshot: WeatherSnapshot, unit: TemperatureUnit): String {
    val builder = StringBuilder(snapshot.cityName)

    val min = snapshot.temperatureMin(unit)?.toInt()
    val max = snapshot.temperatureMax(unit)?.toInt()
    if (min != null && max != null) {
        builder.append("  ").append(min).append("-").append(max).append("\u00B0")
    }

    val age = ageLabel(snapshot.observedAtEpochMillis)
    if (age != null) builder.append("  ").append(age)

    return builder.toString()
}

/**
 * A human age for a cached reading, or `null` when there is nothing worth saying.
 *
 * A reading younger than a minute shows no age at all: "just now" is noise on a
 * screen the user glances at hundreds of times a day.
 */
private fun ageLabel(observedAtEpochMillis: Long): String? {
    if (observedAtEpochMillis <= 0L) return null
    val elapsed = System.currentTimeMillis() - observedAtEpochMillis
    if (elapsed < TimeUnit.MINUTES.toMillis(1)) return null

    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    if (minutes < 60) return "${minutes}m ago"

    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    if (hours < 24) return "${hours}h ago"

    return "${TimeUnit.MILLISECONDS.toDays(elapsed)}d ago"
}

/**
 * The favourites strip.
 *
 * A `LazyRow` rather than a `Row` so a user with a lot of favourites scrolls them
 * instead of running out of width.
 */
@Composable
private fun FavouritesStrip(
    entries: List<AppListEntry>,
    configuration: LauncherConfiguration,
    loadIcon: IconLoader,
    pxFor: PxConverter,
    onClick: (AppListEntry) -> Unit,
    onLongClick: (AppListEntry) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm),
    ) {
        items(items = entries, key = { it.key.storageKey() }) { entry ->
            IconTile(
                entry = entry,
                iconSize = configuration.iconSize.dp,
                showLabel = configuration.showLabels,
                loadIcon = loadIcon,
                pxFor = pxFor,
                onClick = { onClick(entry) },
                onLongClick = { onLongClick(entry) },
            )
        }
    }
}

/** A quiet row at the bottom of the list that opens the sort picker. */
@Composable
private fun SortFooter(mode: SortMode, onClick: () -> Unit) {
    val colors = minimalFlowColors()
    Text(
        text = "Sorted by ${mode.title}",
        color = colors.secondaryText,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.lg),
    )
}

@Composable
private fun CircleButton(label: String, onClick: () -> Unit) {
    val colors = minimalFlowColors()
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1).uppercase(),
            color = colors.secondaryText,
            fontSize = 14.sp,
        )
    }
}

/** The time and date, on the user's 12/24-hour preference. */
private data class ClockReading(val time: String, val date: String)

@Composable
private fun rememberClock(): ClockReading {
    val context = LocalContext.current
    val locale = Locale.getDefault()

    // Ticks once a minute, aligned to the minute boundary: a launcher clock does
    // not need second precision, and a per-second recomposition would redraw the
    // entire home screen sixty times a minute for nothing.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            delay(TimeUnit.MINUTES.toMillis(1) - (now % TimeUnit.MINUTES.toMillis(1)))
            tick++
        }
    }

    return remember(tick, locale) {
        val now = Date()
        ClockReading(
            // getTimeFormat already applies the user's 12/24-hour setting, and it
            // is a cached object per context, so it is not re-parsed every minute.
            time = DateFormat.getTimeFormat(context).format(now),
            date = SimpleDateFormat("EEEE d MMMM", locale).format(now),
        )
    }
}

private fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..21 -> "Good evening"
        else -> "Hello"
    }
}

/**
 * A one-character stand-in for a weather glyph.
 *
 * Material icons would mean shipping a font or nine vector assets to draw nine
 * shapes; the launcher already has a text pipeline, and a plain symbol keeps the
 * weather line free of any new dependency.
 */
private fun glyphSymbol(glyph: WeatherGlyph): String = when (glyph) {
    WeatherGlyph.SUN -> "\u2600"
    WeatherGlyph.CLOUD_SUN -> "\u26C5"
    WeatherGlyph.CLOUD -> "\u2601"
    WeatherGlyph.FOG -> "\u2591"
    WeatherGlyph.DRIZZLE -> "\u2592"
    WeatherGlyph.RAIN -> "\u2614"
    WeatherGlyph.SLEET -> "\u2604"
    WeatherGlyph.SNOW -> "\u2744"
    WeatherGlyph.STORM -> "\u26A1"
}
