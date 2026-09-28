package com.minimalflow.launcher.ui.settings

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minimalflow.launcher.core.backup.BACKUP_FILE_NAME
import com.minimalflow.launcher.core.backup.BACKUP_IMPORT_MIME_TYPES
import com.minimalflow.launcher.core.backup.BACKUP_MIME_TYPE
import com.minimalflow.launcher.core.model.ThemeConfig
import com.minimalflow.launcher.core.themes.BuiltInThemes
import com.minimalflow.launcher.core.themes.MinimalFlowTheme
import com.minimalflow.launcher.core.themes.ThemeRepository
import com.minimalflow.launcher.core.ui.Spacing
import com.minimalflow.launcher.core.ui.cornerRadius
import com.minimalflow.launcher.core.ui.minimalFlowColors
import com.minimalflow.launcher.core.ui.screenPadding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The settings app.
 *
 * A separate activity and a separate task from the launcher on purpose. Settings
 * is somewhere the user goes to change things and then leave; sharing a task with
 * home would mean pressing back from settings drops them onto a launcher that has
 * already been reconfigured under them.
 *
 * Backup uses the Storage Access Framework rather than a storage permission, so
 * the app never asks for access to the user's files - it only receives the single
 * document the user picked.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeRepository: ThemeRepository

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SettingsRoot(
                viewModel = viewModel,
                themeRepository = themeRepository,
                onClose = ::finish,
            )
        }
    }
}

@Composable
private fun SettingsRoot(
    viewModel: SettingsViewModel,
    themeRepository: ThemeRepository,
    onClose: () -> Unit,
) {
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()

    // A suspending theme lookup, resolved before the first frame that needs it.
    val theme by produceState<ThemeConfig?>(initialValue = null, key1 = configuration.themeId) {
        value = runCatching { themeRepository.resolveOrDefault(configuration.themeId) }.getOrNull()
    }

    MinimalFlowTheme(
        theme = theme ?: BuiltInThemes.default,
        configuration = configuration,
    ) {
        SettingsScreen(
            viewModel = viewModel,
            configuration = configuration,
            onClose = onClose,
        )
    }
}

/**
 * The settings list.
 *
 * Every row is a switch, a value or a link, and nothing is more than one tap deep.
 * There is no "advanced" section: a setting that is genuinely obscure is a setting
 * most people should leave alone, and hiding it behind a gate only makes it
 * harder to find for the one person who needs it.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    configuration: com.minimalflow.launcher.core.model.LauncherConfiguration,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()

    // The system file picker. No permission is involved: the user grants access to
    // the one document they chose, and that grant does not outlive the result.
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
    ) { uri: Uri? -> if (uri != null) viewModel.exportBackup(uri) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> if (uri != null) viewModel.importBackup(uri) }

    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.text)
        viewModel.consumeMessage()
    }

    Column(modifier = modifier.fillMaxSize()) {
        SettingsHeader(onClose = onClose)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            section("Home screen") {
                ToggleRow(
                    title = "Show clock",
                    subtitle = "The large time above the list.",
                    checked = configuration.showClock,
                    onCheckedChange = viewModel::setShowClock,
                )
                ToggleRow(
                    title = "Show date",
                    checked = configuration.showDate,
                    onCheckedChange = viewModel::setShowDate,
                )
                ToggleRow(
                    title = "Show greeting",
                    checked = configuration.showGreeting,
                    onCheckedChange = viewModel::setShowGreeting,
                )
                ToggleRow(
                    title = "Favourites strip",
                    checked = configuration.showFavorites,
                    onCheckedChange = viewModel::setShowFavorites,
                )
                ToggleRow(
                    title = "A to Z section headers",
                    checked = configuration.sectionHeadersEnabled,
                    onCheckedChange = viewModel::setSectionHeaders,
                )
            }

            section("List") {
                ValueRow("Sort by", configuration.sortingMode.title, viewModel::cycleSortMode)
                ValueRow(
                    "Show icons",
                    if (configuration.showIcons) "On" else "Off",
                    viewModel::toggleIcons,
                )
                ValueRow(
                    "Show labels",
                    if (configuration.showLabels) "On" else "Off",
                    viewModel::toggleLabels,
                )
                ValueRow(
                    "Icon size",
                    "${configuration.iconSize} dp",
                    viewModel::cycleIconSize,
                )
                ValueRow(
                    "Row height",
                    "${configuration.rowHeight} dp",
                    viewModel::cycleRowHeight,
                )
            }

            section("Appearance") {
                ValueRow(
                    "Theme",
                    BuiltInThemes.byId(configuration.themeId)?.name ?: "Custom",
                    viewModel::cycleTheme,
                )
                ValueRow("Font size", "${configuration.fontSize}%", viewModel::cycleFontSize)
            }

            section("Weather") {
                ToggleRow(
                    title = "Show weather",
                    subtitle = "Fetches from Open-Meteo. Off by default.",
                    checked = configuration.showWeather,
                    onCheckedChange = viewModel::setShowWeather,
                )
                if (configuration.showWeather) {
                    ValueRow(
                        "City",
                        configuration.weather.cityName.ifBlank { "Not set" },
                        viewModel::cycleCity,
                    )
                    ToggleRow(
                        title = "Use device location",
                        subtitle = "Needs the location permission. Off by default.",
                        checked = configuration.weather.useDeviceLocation,
                        onCheckedChange = viewModel::setUseDeviceLocation,
                    )
                }
            }

            section("Privacy") {
                ToggleRow(
                    title = "Usage tracking",
                    subtitle = "Needed for 'recently used' and 'most used' sorting. " +
                        "Nothing ever leaves the device.",
                    checked = configuration.privacy.usageTrackingEnabled,
                    onCheckedChange = viewModel::setUsageTracking,
                )
                ToggleRow(
                    title = "Search history",
                    checked = configuration.privacy.searchHistoryEnabled,
                    onCheckedChange = viewModel::setSearchHistory,
                )
                ValueRow("Clear search history", "", viewModel::clearSearchHistory)
            }

            section("Backup") {
                ValueRow(
                    "Export a backup",
                    "",
                    { exportPicker.launch(BACKUP_FILE_NAME) },
                )
                ValueRow(
                    "Import a backup",
                    "",
                    { importPicker.launch(BACKUP_IMPORT_MIME_TYPES) },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

@Composable
private fun SettingsHeader(onClose: () -> Unit) {
    val colors = minimalFlowColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Settings",
            color = colors.primaryText,
            fontSize = 20.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Done",
            color = colors.accent,
            fontSize = 15.sp,
            modifier = Modifier.clickable(onClick = onClose).padding(Spacing.sm),
        )
    }
}

// ---------------------------------------------------------------- list primitives

private fun LazyListScope.section(title: String, content: @Composable () -> Unit) {
    item(key = "section-$title") {
        Text(
            text = title,
            color = minimalFlowColors().accent,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
        )
    }
    item(key = "body-$title") {
        val colors = minimalFlowColors()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(cornerRadius())),
        ) {
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    val colors = minimalFlowColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = colors.primaryText, fontSize = 15.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.xxs))
                Text(text = subtitle, color = colors.secondaryText, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    val colors = minimalFlowColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = colors.primaryText,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(text = value, color = colors.secondaryText, fontSize = 14.sp)
        }
    }
}
