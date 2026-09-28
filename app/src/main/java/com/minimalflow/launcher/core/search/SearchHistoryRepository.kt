package com.minimalflow.launcher.core.search

import com.minimalflow.launcher.core.data.SearchHistoryDao
import com.minimalflow.launcher.core.data.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search terms the user typed before.
 *
 * Stored in the launcher's own Room database, off by default. When history is
 * disabled nothing is written at all, and [clear] wipes anything an earlier
 * version may have left behind. The data never leaves the device.
 */
@Singleton
class SearchHistoryRepository @Inject constructor(
    private val searchHistoryDao: SearchHistoryDao,
) {

    /** Recent terms, most recent first, capped at [limit]. */
    fun recentTerms(limit: Int = 10): Flow<List<String>> =
        searchHistoryDao.observeRecent(limit.coerceAtLeast(1)).map { rows -> rows.map { it.term } }

    suspend fun all(): List<SearchHistoryEntity> = searchHistoryDao.getAll()

    /**
     * Records one search. Terms shorter than [MIN_TERM_LENGTH] are ignored so a
     * single stray character does not pollute the list.
     *
     * [now] is injectable, which keeps the "most recent first" ordering testable.
     */
    suspend fun record(term: String, now: Long = System.currentTimeMillis()) {
        val cleaned = term.trim().lowercase()
        if (cleaned.length < MIN_TERM_LENGTH) return

        val existing = searchHistoryDao.find(cleaned)
        searchHistoryDao.upsert(
            SearchHistoryEntity(
                term = cleaned,
                lastUsedAt = now,
                useCount = (existing?.useCount ?: 0) + 1,
            ),
        )
        searchHistoryDao.deleteOutsideNewest(MAX_STORED_TERMS)
    }

    suspend fun deleteTerm(term: String) {
        val cleaned = term.trim().lowercase()
        if (cleaned.isEmpty()) return
        searchHistoryDao.delete(cleaned)
    }

    suspend fun clear() = searchHistoryDao.clear()

    companion object {
        const val MIN_TERM_LENGTH = 2
        const val MAX_STORED_TERMS = 100
    }
}
