package com.minimalflow.launcher.core.data

import com.minimalflow.launcher.core.model.ColorMode
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.LauncherConfigurationExtras
import com.minimalflow.launcher.core.model.SortMode
import com.minimalflow.launcher.core.model.WeatherSnapshot
import com.minimalflow.launcher.core.data.EntityMappers.toConfiguration
import com.minimalflow.launcher.core.data.EntityMappers.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for reading and writing launcher settings.
 *
 * Views observe [configuration]; view models mutate it through [update], which
 * always reads the current row, applies the change and writes it back inside a
 * mutex. That makes "toggle two switches quickly" safe without every call site
 * having to think about lost updates, and it guarantees whatever is written is
 * already [LauncherConfiguration.sanitised].
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val settingsDao: LauncherSettingsDao,
    private val appPreferences: AppPreferences,
) {

    private val writeLock = Mutex()

    /** Never emits twice in a row with the same value, so Compose can skip work. */
    val configuration: Flow<LauncherConfiguration> = settingsDao.observe()
        .map { entity -> (entity?.toConfiguration() ?: LauncherConfiguration()).sanitised() }
        .distinctUntilChanged()

    suspend fun currentConfiguration(): LauncherConfiguration =
        (settingsDao.get()?.toConfiguration() ?: LauncherConfiguration()).sanitised()

    /** Read-modify-write, serialised so concurrent edits cannot overwrite. */
    suspend fun update(transform: (LauncherConfiguration) -> LauncherConfiguration) {
        writeLock.withLock {
            val existing = settingsDao.get()?.toConfiguration() ?: LauncherConfiguration()
            val updated = transform(existing).sanitised()
            settingsDao.upsert(updated.toEntity())
        }
    }

    /** Replaces the whole configuration, used by "restore from backup". */
    suspend fun replaceAll(configuration: LauncherConfiguration) {
        writeLock.withLock { settingsDao.upsert(configuration.sanitised().toEntity()) }
    }

    // ------------------------------------------------------------------ resets

    /**
     * Restores one category to its defaults while leaving the other categories
     * alone, which is what the "Reset" button on every settings page does.
     */
    suspend fun resetCategory(category: SettingsCategory) {
        val defaults = LauncherConfiguration()
        update { current ->
            when (category) {
                SettingsCategory.HOME -> current.copy(
                    showClock = defaults.showClock,
                    showDate = defaults.showDate,
                    extras = current.extras.copy(
                        showGreeting = defaults.extras.showGreeting,
                        sectionHeadersEnabled = defaults.extras.sectionHeadersEnabled,
                        stickyHeaders = defaults.extras.stickyHeaders,
                        clockFormat24h = defaults.extras.clockFormat24h,
                        showSeconds = defaults.extras.showSeconds,
                        favoritesLayout = defaults.extras.favoritesLayout,
                        favoritesMaxItems = defaults.extras.favoritesMaxItems,
                        favoritesShowIcons = defaults.extras.favoritesShowIcons,
                        favoritesShowLabels = defaults.extras.favoritesShowLabels,
                        topPaddingDp = defaults.extras.topPaddingDp,
                        bottomPaddingDp = defaults.extras.bottomPaddingDp,
                    ),
                )

                SettingsCategory.APP_LIST -> current.copy(
                    sortingMode = defaults.sortingMode,
                    showIcons = defaults.showIcons,
                    showLabels = defaults.showLabels,
                    showAlphabetRail = defaults.showAlphabetRail,
                    rowHeight = defaults.rowHeight,
                    iconSize = defaults.iconSize,
                    extras = current.extras.copy(
                        sectionHeadersEnabled = defaults.extras.sectionHeadersEnabled,
                        alphabetRailPosition = defaults.extras.alphabetRailPosition,
                        listSpacingDp = defaults.extras.listSpacingDp,
                        horizontalPaddingDp = defaults.extras.horizontalPaddingDp,
                        fuzzySearch = defaults.extras.fuzzySearch,
                        hideWorkProfileApps = defaults.extras.hideWorkProfileApps,
                        showRecentApps = defaults.extras.showRecentApps,
                        searchEnabled = defaults.extras.searchEnabled,
                    ),
                )

                SettingsCategory.APPEARANCE -> current.copy(
                    themeMode = defaults.themeMode,
                    themeId = defaults.themeId,
                    accentColor = defaults.accentColor,
                    backgroundColor = defaults.backgroundColor,
                    fontSize = defaults.fontSize,
                    animationEnabled = defaults.animationEnabled,
                    extras = current.extras.copy(
                        typography = defaults.extras.typography,
                        wallpaper = defaults.extras.wallpaper,
                        iconConfig = defaults.extras.iconConfig,
                        animationSpeedPercent = defaults.extras.animationSpeedPercent,
                        hapticsEnabled = defaults.extras.hapticsEnabled,
                    ),
                )

                SettingsCategory.GESTURES -> current.copy(
                    extras = current.extras.copy(gestureConfig = defaults.extras.gestureConfig),
                )

                SettingsCategory.WIDGETS -> current.copy(
                    extras = current.extras.copy(widgetConfig = defaults.extras.widgetConfig),
                )

                SettingsCategory.PRIVACY -> current.copy(
                    showWeather = defaults.showWeather,
                    extras = current.extras.copy(
                        privacy = defaults.extras.privacy,
                        weather = defaults.extras.weather,
                    ),
                )
            }
        }
    }

    /** Restores every setting. User created content is deliberately kept. */
    suspend fun resetAllSettings() {
        writeLock.withLock { settingsDao.upsert(LauncherConfiguration().sanitised().toEntity()) }
    }

    // ------------------------------------------------------- DataStore-backed

    val onboardingCompleted: Flow<Boolean> get() = appPreferences.onboardingCompleted
    val previousHomePackage: Flow<String?> get() = appPreferences.previousHomePackage
    val defaultLauncherPromptShown: Flow<Boolean> get() = appPreferences.defaultLauncherPromptShown
    val pendingSearchQuery: Flow<String> get() = appPreferences.pendingSearchQuery
    val weatherCache: Flow<WeatherSnapshot?> get() = appPreferences.weatherCache
    val lastBackupAt: Flow<Long> get() = appPreferences.lastBackupAt

    suspend fun setOnboardingCompleted(value: Boolean) =
        appPreferences.setOnboardingCompleted(value)

    suspend fun setPreviousHomePackage(value: String?) =
        appPreferences.setPreviousHomePackage(value)

    suspend fun setDefaultLauncherPromptShown(value: Boolean) =
        appPreferences.setDefaultLauncherPromptShown(value)

    suspend fun setPendingSearchQuery(value: String) =
        appPreferences.setPendingSearchQuery(value)

    suspend fun setWeatherCache(value: WeatherSnapshot?) =
        appPreferences.setWeatherCache(value)

    suspend fun setLastBackupAt(value: Long) = appPreferences.setLastBackupAt(value)

    suspend fun clearPreferences() = appPreferences.clear()
}

/** The reset buttons offered by the settings screens. */
enum class SettingsCategory(val title: String) {
    HOME("Home screen"),
    APP_LIST("App list"),
    APPEARANCE("Appearance"),
    GESTURES("Gestures"),
    WIDGETS("Widgets"),
    PRIVACY("Privacy"),
}

/** Convenience overloads used by view models for the most common edits. */
suspend fun PreferencesRepository.setColorMode(mode: ColorMode) = update { it.copy(themeMode = mode) }

suspend fun PreferencesRepository.setSortMode(mode: SortMode) = update { it.copy(sortingMode = mode) }

suspend fun PreferencesRepository.setExtras(transform: (LauncherConfigurationExtras) -> LauncherConfigurationExtras) =
    update { it.copy(extras = transform(it.extras)) }
