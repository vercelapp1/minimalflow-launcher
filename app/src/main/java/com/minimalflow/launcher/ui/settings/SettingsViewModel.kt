package com.minimalflow.launcher.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minimalflow.launcher.core.backup.BackupRepository
import com.minimalflow.launcher.core.backup.RestoreResult
import com.minimalflow.launcher.core.data.PreferencesRepository
import com.minimalflow.launcher.core.data.setExtras
import com.minimalflow.launcher.core.data.setSortMode
import com.minimalflow.launcher.core.model.IconLimits
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.ListLimits
import com.minimalflow.launcher.core.model.SortMode
import com.minimalflow.launcher.core.model.TypographyLimits
import com.minimalflow.launcher.core.search.SearchRepository
import com.minimalflow.launcher.core.themes.BuiltInThemes
import com.minimalflow.launcher.core.weather.WeatherRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A one-shot message for the settings screen. */
data class SettingsMessage(val id: Long, val text: String)

/**
 * Settings state.
 *
 * The numeric settings (size, row height, font scale) cycle through their
 * permitted values rather than opening a slider. A slider is the wrong control
 * when there are only a handful of sensible steps, and it is the wrong control
 * for something a user adjusts once and then never again - the point is to reach
 * the right value in one tap, not to explore a continuum.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val backupRepository: BackupRepository,
    private val searchRepository: SearchRepository,
    private val weatherRepository: WeatherRepository,
) : ViewModel() {

    val configuration: StateFlow<LauncherConfiguration> = preferences.configuration
        .stateIn(viewModelScope, SharingStarted.Eagerly, LauncherConfiguration())

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    private var messageId = 0L

    // ------------------------------------------------------------------- home

    fun setShowClock(value: Boolean) = update { it.copy(showClock = value) }

    fun setShowDate(value: Boolean) = update { it.copy(showDate = value) }

    fun setShowFavorites(value: Boolean) = update { it.copy(showFavorites = value) }

    fun setShowGreeting(value: Boolean) = updateExtras { extras ->
        extras.copy(showGreeting = value)
    }

    fun setSectionHeaders(value: Boolean) = updateExtras { extras ->
        extras.copy(sectionHeadersEnabled = value)
    }

    // -------------------------------------------------------------------- list

    fun toggleIcons() = update { it.copy(showIcons = !it.showIcons) }

    fun toggleLabels() = update { it.copy(showLabels = !it.showLabels) }

    fun cycleSortMode() {
        val modes = SortMode.entries
        val next = modes[(modes.indexOf(configuration.value.sortingMode) + 1) % modes.size]
        viewModelScope.launch { preferences.setSortMode(next) }
    }

    fun cycleIconSize() {
        val steps = ICON_SIZE_STEPS
        val current = configuration.value.iconSize
        val index = steps.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: steps.size - 1
        update { it.copy(iconSize = steps[(index + 1) % steps.size]) }
    }

    fun cycleRowHeight() {
        val steps = ROW_HEIGHT_STEPS
        val current = configuration.value.rowHeight
        val index = steps.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: steps.size - 1
        update { it.copy(rowHeight = steps[(index + 1) % steps.size]) }
    }

    // -------------------------------------------------------------- appearance

    fun cycleFontSize() {
        val steps = FONT_STEPS
        val current = configuration.value.fontSize
        val index = steps.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: steps.size - 1
        update { it.copy(fontSize = steps[(index + 1) % steps.size]) }
    }

    fun cycleTheme() {
        val themes = BuiltInThemes.all.map { it.id }
        val current = configuration.value.themeId
        val index = themes.indexOf(current).takeIf { it >= 0 } ?: 0
        update { it.copy(themeId = themes[(index + 1) % themes.size]) }
    }

    // ----------------------------------------------------------------- weather

    fun setShowWeather(value: Boolean) = update { it.copy(showWeather = value) }

    fun setUseDeviceLocation(value: Boolean) = updateExtras { extras ->
        extras.copy(weather = extras.weather.copy(useDeviceLocation = value))
    }

    /**
     * Steps through the built-in sample cities.
     *
     * A full city picker needs a search field, a permission flow and its own
     * screen; a cycle proves the feature works and the picker is the natural
     * follow-up rather than something half-built here.
     */
    fun cycleCity() {
        val next = SAMPLE_CITIES[(SAMPLE_CITIES.indexOfFirst { it.first == configuration.value.weather.cityName } + 1)
            .let { if (it < 0) 0 else it } % SAMPLE_CITIES.size]
        updateExtras { extras ->
            extras.copy(
                weather = extras.weather.copy(
                    cityName = next.first,
                    latitude = next.second,
                    longitude = next.third,
                ),
            )
        }
        viewModelScope.launch { weatherRepository.refresh() }
    }

    // ----------------------------------------------------------------- privacy

    fun setUsageTracking(value: Boolean) = updateExtras { extras ->
        extras.copy(privacy = extras.privacy.copy(usageTrackingEnabled = value))
    }

    fun setSearchHistory(value: Boolean) = updateExtras { extras ->
        extras.copy(privacy = extras.privacy.copy(searchHistoryEnabled = value))
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            searchRepository.clearHistory()
            showMessage("Search history cleared")
        }
    }

    // ------------------------------------------------------------------ backup

    fun exportBackup(target: Uri) {
        viewModelScope.launch {
            val bytes = backupRepository.exportTo(target)
            showMessage(
                if (bytes == null) "Could not write the backup" else "Backup saved",
            )
        }
    }

    fun importBackup(source: Uri) {
        viewModelScope.launch {
            when (val result = backupRepository.restoreFrom(source)) {
                is RestoreResult.Restored -> {
                    val summary = result.summary
                    showMessage(
                        "Restored ${summary.favorites} favourites, " +
                            "${summary.aliases} renames, ${summary.customThemes} themes",
                    )
                }

                is RestoreResult.Unreadable -> showMessage("Could not read that backup")
                is RestoreResult.UnsupportedFormat -> {
                    showMessage("That backup is from a newer version")
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun update(transform: (LauncherConfiguration) -> LauncherConfiguration) {
        viewModelScope.launch { preferences.update(transform) }
    }

    private fun updateExtras(
        transform: (com.minimalflow.launcher.core.model.LauncherConfigurationExtras) ->
            com.minimalflow.launcher.core.model.LauncherConfigurationExtras,
    ) {
        viewModelScope.launch { preferences.setExtras(transform) }
    }

    private fun showMessage(text: String) {
        _message.value = SettingsMessage(id = ++messageId, text = text)
    }

    private companion object {
        val ICON_SIZE_STEPS = intArrayOf(32, 40, 46, 56, 64)
        val ROW_HEIGHT_STEPS = intArrayOf(44, 52, 64, 80, 100)
        val FONT_STEPS = intArrayOf(80, 90, 100, 110, 120, 130, 140)

        /** Name, latitude, longitude. */
        val SAMPLE_CITIES = listOf(
            Triple("London", 51.5072, -0.1276),
            Triple("Berlin", 52.5200, 13.4050),
            Triple("New York", 40.7128, -74.0060),
            Triple("Tokyo", 35.6762, 139.6503),
            Triple("Sydney", -33.8688, 151.2093),
        )
    }
}
