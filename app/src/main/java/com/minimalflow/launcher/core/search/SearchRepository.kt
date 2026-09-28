package com.minimalflow.launcher.core.search

import com.minimalflow.launcher.core.apps.AppRepository
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.LauncherConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/** A finished search: the matches plus the bookkeeping the search screen needs. */
data class SearchResponse(
    val query: String,
    val results: List<SearchResult>,
    val recentTerms: List<String> = emptyList(),
) {
    val isEmptyQuery: Boolean get() = query.isBlank()
    val hasResults: Boolean get() = results.isNotEmpty()
}

/**
 * Turns a query into ranked results.
 *
 * The index is the launcher's in-memory app list, so a keystroke costs one
 * linear pass over a few hundred short strings and no database access at all.
 * Search history is only read when the box is empty, and only written when the
 * user actually launches something.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val appRepository: AppRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
) {

    /**
     * Streams results for [query]. The scan runs on [Dispatchers.Default] so
     * typing never competes with the main thread.
     */
    fun search(query: String, configuration: LauncherConfiguration): Flow<SearchResponse> =
        combine(
            appRepository.searchableApps,
            recentTerms(configuration),
        ) { entries, recent ->
            SearchResponse(
                query = query,
                results = AppSearchEngine.search(
                    entries = entries,
                    rawQuery = query,
                    allowFuzzy = configuration.extras.fuzzySearch,
                ),
                recentTerms = recent,
            )
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    /**
     * Suggestions for a partial query: stored history terms that match, then
     * app names that match. Feeds the row above the keyboard.
     */
    fun suggestions(query: String, configuration: LauncherConfiguration): Flow<List<String>> =
        combine(
            appRepository.searchableApps,
            recentTerms(configuration),
        ) { entries, recent ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                recent
            } else {
                val appNames = AppSearchEngine.search(
                    entries = entries,
                    rawQuery = trimmed,
                    limit = APP_SUGGESTION_LIMIT,
                    allowFuzzy = false,
                ).map { it.entry.displayLabel }
                val historyHits = recent
                    .filter { it.contains(trimmed, ignoreCase = true) }
                    .map { it.replaceFirstChar(Char::uppercase) }
                (historyHits + appNames).distinct().take(SUGGESTION_LIMIT)
            }
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    /** Records a completed search, but only when the user opted into history. */
    suspend fun recordQuery(query: String, configuration: LauncherConfiguration) {
        if (!configuration.privacy.searchHistoryEnabled) return
        searchHistoryRepository.record(query)
    }

    suspend fun clearHistory() = searchHistoryRepository.clear()

    suspend fun removeHistoryTerm(term: String) = searchHistoryRepository.deleteTerm(term)

    /** The app list, used by the "choose an app" picker in gesture settings. */
    fun allApps(): Flow<List<AppListEntry>> = appRepository.searchableApps

    private fun recentTerms(configuration: LauncherConfiguration): Flow<List<String>> {
        if (!configuration.privacy.searchHistoryEnabled) return flowOf(emptyList())
        val limit = configuration.privacy.searchHistoryLimit.coerceIn(1, 50)
        return searchHistoryRepository.recentTerms(limit)
    }

    private companion object {
        const val SUGGESTION_LIMIT = 6
        const val APP_SUGGESTION_LIMIT = 6
    }
}
