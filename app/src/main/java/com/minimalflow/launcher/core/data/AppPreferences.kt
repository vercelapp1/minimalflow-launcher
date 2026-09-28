package com.minimalflow.launcher.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.minimalflow.launcher.core.model.WeatherSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "minimalflow_settings",
)

/**
 * Typed wrapper around the Jetpack DataStore file.
 *
 * DataStore holds the handful of values that are neither configuration nor user
 * created content: onboarding progress, the last default-launcher package, the
 * weather cache and the in-flight search query. Everything the user customises
 * lives in Room, which keeps the two stores from overlapping.
 *
 * Every read is wrapped so a corrupted file degrades to "nothing set yet"
 * instead of crashing the launcher on start-up.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {

    private val store: DataStore<Preferences> get() = context.preferencesDataStore

    private val safeData: Flow<Preferences> = store.data.catch { throwable ->
        if (throwable is IOException) {
            emit(emptyPreferences())
        } else {
            throw throwable
        }
    }

    // ------------------------------------------------------------- onboarding

    val onboardingCompleted: Flow<Boolean> =
        safeData.map { it[Keys.ONBOARDING_COMPLETED] ?: false }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    // ------------------------------------------------------- default launcher

    val previousHomePackage: Flow<String?> =
        safeData.map { it[Keys.PREVIOUS_HOME_PACKAGE] }

    suspend fun setPreviousHomePackage(packageName: String?) {
        store.edit { preferences ->
            if (packageName.isNullOrBlank()) {
                preferences.remove(Keys.PREVIOUS_HOME_PACKAGE)
            } else {
                preferences[Keys.PREVIOUS_HOME_PACKAGE] = packageName
            }
        }
    }

    val defaultLauncherPromptShown: Flow<Boolean> =
        safeData.map { it[Keys.DEFAULT_LAUNCHER_PROMPT_SHOWN] ?: false }

    suspend fun setDefaultLauncherPromptShown(shown: Boolean) {
        store.edit { it[Keys.DEFAULT_LAUNCHER_PROMPT_SHOWN] = shown }
    }

    // ---------------------------------------------------------------- search

    /** Restores an in-progress search after the process is killed. */
    val pendingSearchQuery: Flow<String> = safeData.map { it[Keys.PENDING_SEARCH_QUERY] ?: "" }

    suspend fun setPendingSearchQuery(query: String) {
        store.edit { preferences ->
            if (query.isBlank()) {
                preferences.remove(Keys.PENDING_SEARCH_QUERY)
            } else {
                preferences[Keys.PENDING_SEARCH_QUERY] = query
            }
        }
    }

    // --------------------------------------------------------------- weather

    val weatherCache: Flow<WeatherSnapshot?> = safeData.map { preferences ->
        val json = preferences[Keys.WEATHER_CACHE] ?: return@map null
        runCatching { EntityMappers.json.decodeFromString(WeatherSnapshot.serializer(), json) }
            .getOrNull()
    }

    suspend fun setWeatherCache(snapshot: WeatherSnapshot?) {
        store.edit { preferences ->
            if (snapshot == null) {
                preferences.remove(Keys.WEATHER_CACHE)
            } else {
                preferences[Keys.WEATHER_CACHE] =
                    EntityMappers.json.encodeToString(WeatherSnapshot.serializer(), snapshot)
            }
        }
    }

    // ---------------------------------------------------------------- backup

    val lastBackupAt: Flow<Long> = safeData.map { it[Keys.LAST_BACKUP_AT] ?: 0L }

    suspend fun setLastBackupAt(timestamp: Long) {
        store.edit { it[Keys.LAST_BACKUP_AT] = timestamp }
    }

    /** Wipes everything this launcher stores outside of Room. */
    suspend fun clear() {
        store.edit { it.clear() }
    }

    suspend fun snapshot(): Preferences = safeData.first()

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val PREVIOUS_HOME_PACKAGE = stringPreferencesKey("previous_home_package")
        val DEFAULT_LAUNCHER_PROMPT_SHOWN = booleanPreferencesKey("default_launcher_prompt_shown")
        val PENDING_SEARCH_QUERY = stringPreferencesKey("pending_search_query")
        val WEATHER_CACHE = stringPreferencesKey("weather_cache")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    }
}
