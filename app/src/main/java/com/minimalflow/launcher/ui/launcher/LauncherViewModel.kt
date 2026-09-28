package com.minimalflow.launcher.ui.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minimalflow.launcher.core.apps.AppLaunchService
import com.minimalflow.launcher.core.apps.AppRepository
import com.minimalflow.launcher.core.apps.AppSection
import com.minimalflow.launcher.core.apps.AppSortingService
import com.minimalflow.launcher.core.apps.LaunchResult
import com.minimalflow.launcher.core.apps.UninstallResult
import com.minimalflow.launcher.core.data.PreferencesRepository
import com.minimalflow.launcher.core.data.setSortMode
import com.minimalflow.launcher.core.lifecycle.DefaultHomeCoordinator
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.SortMode
import com.minimalflow.launcher.core.model.WeatherSnapshot
import com.minimalflow.launcher.core.search.SearchRepository
import com.minimalflow.launcher.core.search.SearchResponse
import com.minimalflow.launcher.core.weather.WeatherRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which surface the launcher is currently showing. */
enum class LauncherMode { Home, Search }

/** What the home screen has open on top of itself, if anything. */
sealed interface HomeSheet {
    data object None : HomeSheet

    /** The per-app long-press menu. */
    data class AppMenu(val key: AppKey) : HomeSheet

    data object SortModePicker : HomeSheet
}

/** A one-shot message for the user, surfaced as a snackbar. */
data class LauncherMessage(val id: Long, val text: String)

/**
 * State for the launcher itself.
 *
 * One view model covers home and search rather than one each: both read the same
 * app list and the same settings, and splitting them would let the search results
 * and the list underneath disagree for a frame after an install.
 *
 * It deliberately does *not* implement `GestureHost`. The gestures that have to
 * start an activity live on the activity, which is the only thing that can, and
 * forwarding them through here would mean holding a `Context` in a view model.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val preferences: PreferencesRepository,
    private val searchRepository: SearchRepository,
    private val launchService: AppLaunchService,
    private val weatherRepository: WeatherRepository,
    private val defaultHome: DefaultHomeCoordinator,
    private val sorting: AppSortingService,
) : ViewModel() {

    private val _mode = MutableStateFlow(LauncherMode.Home)
    val mode: StateFlow<LauncherMode> = _mode.asStateFlow()

    private val _sheet = MutableStateFlow<HomeSheet>(HomeSheet.None)
    val sheet: StateFlow<HomeSheet> = _sheet.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _message = MutableStateFlow<LauncherMessage?>(null)
    val message: StateFlow<LauncherMessage?> = _message.asStateFlow()

    private var messageId = 0L

    val configuration: StateFlow<LauncherConfiguration> = preferences.configuration
        .stateIn(viewModelScope, SharingStarted.Eagerly, LauncherConfiguration())

    /**
     * The visible apps, sorted and grouped.
     *
     * Grouping is skipped when section headers are off, which avoids building a
     * `LinkedHashMap` on every configuration change for a list that will not use it.
     */
    val sections: StateFlow<List<AppSection>> = combine(
        appRepository.entries,
        preferences.configuration,
    ) { entries, config ->
        val sorted = sorting.sort(entries, effectiveSortMode(config))
        if (config.sectionHeadersEnabled) {
            sorting.groupIntoSections(sorted)
        } else {
            listOf(AppSection(letter = "", entries = sorted))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val favorites: StateFlow<List<AppListEntry>> = appRepository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val weather: StateFlow<WeatherSnapshot?> = weatherRepository.cached
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /**
     * Search results.
     *
     * `flatMapLatest` over the debounced query is what keeps typing responsive:
     * each keystroke cancels the previous search, so results can never arrive out
     * of order and the list never flickers between two queries.
     */
    val searchResults: StateFlow<SearchResponse?> = _query
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(SearchResponse(query = "", results = emptyList()))
            } else {
                searchRepository.search(query, configuration.value)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Recent terms, shown when the search box is empty and history is enabled. */
    val recentTerms: StateFlow<List<String>> = preferences.configuration
        .map { it.privacy.searchHistoryEnabled }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) searchRepository.suggestions("", configuration.value) else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    init {
        refreshWeather()
    }

    // ------------------------------------------------------------------- actions

    fun launch(entry: AppListEntry) {
        when (launchService.launch(entry)) {
            LaunchResult.Started -> {
                if (configuration.value.privacy.usageTrackingEnabled) {
                    viewModelScope.launch { appRepository.recordLaunch(entry.key) }
                }
            }

            is LaunchResult.Failed -> showMessage("Could not open ${entry.displayLabel}")

            LaunchResult.NotInstalled -> {
                viewModelScope.launch { appRepository.restoreApp(entry.key) }
                showMessage("${entry.displayLabel} is not installed any more")
            }
        }
    }

    fun launchShortcut(key: AppKey, shortcutId: String) {
        when (launchService.startShortcut(key, shortcutId)) {
            LaunchResult.Started -> Unit
            else -> showMessage("That shortcut is no longer available")
        }
    }

    fun openAppInfo(key: AppKey) {
        if (!launchService.openAppInfo(key)) showMessage("Could not open app info")
    }

    fun requestUninstall(entry: AppListEntry) {
        when (launchService.requestUninstall(entry.key)) {
            UninstallResult.RequestSent -> Unit
            else -> showMessage("Cannot uninstall ${entry.displayLabel}")
        }
    }

    // -------------------------------------------------------------------- search

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun openSearch() {
        _mode.value = LauncherMode.Search
    }

    fun closeSearch() {
        _mode.value = LauncherMode.Home
        _query.value = ""
    }

    /** The gesture that means "get me out of search and back to the list". */
    fun openAppList() {
        closeSearch()
        dismissSheet()
    }

    /** Remembers the term before the search surface goes away. */
    fun commitSearch() {
        val term = _query.value
        if (term.isNotBlank()) {
            val config = configuration.value
            viewModelScope.launch { searchRepository.recordQuery(term, config) }
        }
    }

    fun chooseRecentTerm(term: String) {
        _query.value = term
    }

    // --------------------------------------------------------------------- menus

    fun showAppMenu(key: AppKey) {
        _sheet.value = HomeSheet.AppMenu(key)
    }

    fun showSortPicker() {
        _sheet.value = HomeSheet.SortModePicker
    }

    fun dismissSheet() {
        _sheet.value = HomeSheet.None
    }

    fun setSortMode(mode: SortMode) {
        viewModelScope.launch { preferences.setSortMode(mode) }
        dismissSheet()
    }

    fun toggleFavorite(entry: AppListEntry) {
        viewModelScope.launch {
            if (entry.isFavorite) {
                appRepository.removeFavorite(entry.key)
            } else {
                appRepository.addFavorite(entry.key)
            }
        }
    }

    fun hideApp(entry: AppListEntry) {
        viewModelScope.launch {
            appRepository.hideApp(entry.key)
            dismissSheet()
        }
        showMessage("${entry.displayLabel} hidden. Restore it in Settings.")
    }

    // ------------------------------------------------------------------- weather

    fun refreshWeather() {
        viewModelScope.launch { weatherRepository.refresh() }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---------------------------------------------------------------- onboarding

    fun isDefaultLauncher(): Boolean = defaultHome.isDefaultLauncher()

    fun requestDefaultLauncher() = defaultHome.requestDefaultLauncher()

    private fun showMessage(text: String) {
        _message.value = LauncherMessage(id = ++messageId, text = text)
    }

    /**
     * Recent and frequent sorting need usage data, which the user can switch off.
     * Falling back keeps the list usable instead of silently sorting by something
     * the user did not ask for.
     */
    private fun effectiveSortMode(config: LauncherConfiguration): SortMode {
        val mode = config.sortingMode
        if (mode != SortMode.RECENT && mode != SortMode.FREQUENT) return mode
        return if (config.privacy.usageTrackingEnabled) mode else SortMode.DEFAULT
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SEARCH_DEBOUNCE_MILLIS = 120L
    }
}
