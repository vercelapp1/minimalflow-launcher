package com.minimalflow.launcher.core.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.minimalflow.launcher.core.data.AliasDao
import com.minimalflow.launcher.core.data.EntityMappers.toEntity
import com.minimalflow.launcher.core.data.EntityMappers.toModel
import com.minimalflow.launcher.core.data.FavoriteDao
import com.minimalflow.launcher.core.data.HiddenDao
import com.minimalflow.launcher.core.data.PreferencesRepository
import com.minimalflow.launcher.core.data.SearchHistoryDao
import com.minimalflow.launcher.core.data.SearchHistoryEntity
import com.minimalflow.launcher.core.data.UsageDao
import com.minimalflow.launcher.core.data.UsageEventEntity
import com.minimalflow.launcher.core.data.WidgetPlacementDao
import com.minimalflow.launcher.core.themes.ThemeRepository
import com.minimalflow.launcher.di.Dispatcher
import com.minimalflow.launcher.di.DispatcherKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** What a restore attempt did, in enough detail to tell the user. */
sealed interface RestoreResult {
    data class Restored(val summary: RestoreSummary) : RestoreResult

    /** The file could not be read or parsed. */
    data class Unreadable(val reason: String) : RestoreResult

    /** The file parsed but is from a future build. */
    data class UnsupportedFormat(val formatVersion: Int) : RestoreResult
}

/** Counts, so the settings screen can say what changed instead of "done". */
data class RestoreSummary(
    val favorites: Int,
    val hiddenApps: Int,
    val aliases: Int,
    val customThemes: Int,
    val usage: Int,
    val searchTerms: Int,
    val createdAtEpochMillis: Long,
    val fromVersionName: String?,
)

/**
 * Reads and writes a user's whole configuration as one JSON file.
 *
 * Two decisions shape this class:
 *
 *  1. **The user picks the file.** Export and import both go through the Storage
 *     Access Framework, so the launcher needs no storage permission at all and the
 *     user chooses where the copy lives. A "backup" that silently writes into the
 *     app's own sandbox is not a backup.
 *  2. **Restore replaces, it does not merge.** Favourites, hidden apps and aliases
 *     are ordered lists where merging two devices produces duplicates and ordering
 *     nonsense. Every table is therefore cleared before the archive is written
 *     back, so a restore lands in exactly the state it describes.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesRepository,
    private val themeRepository: ThemeRepository,
    private val favoriteDao: FavoriteDao,
    private val hiddenDao: HiddenDao,
    private val aliasDao: AliasDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val usageDao: UsageDao,
    private val widgetDao: WidgetPlacementDao,
    @Dispatcher(DispatcherKind.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    /**
     * Writes the current configuration to [target].
     *
     * Returns the number of bytes written, or `null` when the destination could
     * not be opened - which for SAF is what a revoked or full volume looks like.
     */
    suspend fun exportTo(target: Uri): Long? = withContext(ioDispatcher) {
        val archive = buildArchive()
        val bytes = runCatching {
            json.encodeToString(BackupArchive.serializer(), archive).toByteArray()
        }.getOrElse { error ->
            Log.e(TAG, "Could not serialise the backup", error)
            return@withContext null
        }
        runCatching {
            context.contentResolver.openOutputStream(target, "wt")?.use { stream ->
                stream.write(bytes)
                stream.flush()
                bytes.size.toLong()
            }
        }.getOrElse { error ->
            Log.e(TAG, "Could not write the backup to $target", error)
            null
        }
    }

    /** The archive as text, used by tests and by the "what would be backed up" view. */
    suspend fun exportToString(): String = withContext(ioDispatcher) {
        json.encodeToString(BackupArchive.serializer(), buildArchive())
    }

    /**
     * Reads [source] and applies it.
     *
     * Widget placements are cleared rather than restored: their ids belong to the
     * device that allocated them. The platform still knows which widgets are bound,
     * so the next reconciliation simply adopts them again.
     */
    suspend fun restoreFrom(source: Uri): RestoreResult = withContext(ioDispatcher) {
        val text = runCatching {
            context.contentResolver.openInputStream(source)?.use { stream ->
                stream.readBytes().decodeToString()
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not open $source", error)
            return@withContext RestoreResult.Unreadable("That file could not be opened.")
        } ?: return@withContext RestoreResult.Unreadable("That file was empty.")

        val archive = runCatching { json.decodeFromString(BackupArchive.serializer(), text) }
            .getOrElse { error ->
                Log.w(TAG, "Could not parse $source", error)
                return@withContext RestoreResult.Unreadable("That file is not a MinimalFlow backup.")
            }

        if (!archive.isReadable) {
            return@withContext RestoreResult.UnsupportedFormat(archive.formatVersion)
        }

        applyArchive(archive)
        preferences.setLastBackupAt(System.currentTimeMillis())

        RestoreResult.Restored(
            RestoreSummary(
                favorites = archive.favorites.size,
                hiddenApps = archive.hiddenApps.size,
                aliases = archive.aliases.size,
                customThemes = archive.customThemes.size,
                usage = archive.usage.size,
                searchTerms = archive.searchHistory.size,
                createdAtEpochMillis = archive.createdAtEpochMillis,
                fromVersionName = archive.appVersionName,
            ),
        )
    }

    private suspend fun buildArchive(): BackupArchive {
        val configuration = preferences.currentConfiguration()
        return BackupArchive(
            createdAtEpochMillis = System.currentTimeMillis(),
            appVersionName = appVersionName(),
            configuration = configuration,
            favorites = favoriteDao.getAll().map { it.toModel() },
            hiddenApps = hiddenDao.getAll().map { it.toModel() },
            aliases = aliasDao.getAll().map { it.toModel() },
            customThemes = themeRepository.customThemes.first(),
            usage = usageDao.getAll().map { entity ->
                ArchivedUsage(
                    packageName = entity.packageName,
                    componentName = entity.componentName,
                    userSerial = entity.userSerial,
                    launchCount = entity.launchCount,
                    lastLaunchAt = entity.lastLaunchAt,
                )
            },
            // Only carried over when the user asked for history in the first place.
            searchHistory = if (configuration.privacy.searchHistoryEnabled) {
                searchHistoryDao.getAll().map { ArchivedSearchTerm(it.term, it.lastUsedAt, it.useCount) }
            } else {
                emptyList()
            },
        )
    }

    private suspend fun applyArchive(archive: BackupArchive) {
        preferences.replaceAll(archive.configuration.sanitised())

        favoriteDao.replaceAll(archive.favorites.map { it.toEntity() })

        hiddenDao.clear()
        archive.hiddenApps.forEach { hiddenDao.upsert(it.toEntity()) }

        aliasDao.clear()
        archive.aliases.forEach { aliasDao.upsert(it.toEntity()) }

        themeRepository.clearCustom()
        archive.customThemes.forEach { theme -> themeRepository.save(theme, createdAt = archive.createdAtEpochMillis) }

        usageDao.clear()
        archive.usage.forEach { record ->
            usageDao.upsert(
                UsageEventEntity(
                    packageName = record.packageName,
                    componentName = record.componentName,
                    userSerial = record.userSerial,
                    launchCount = record.launchCount,
                    lastLaunchAt = record.lastLaunchAt,
                ),
            )
        }

        searchHistoryDao.clear()
        archive.searchHistory.forEach { term ->
            searchHistoryDao.upsert(
                SearchHistoryEntity(term = term.term, lastUsedAt = term.lastUsedAt, useCount = term.useCount),
            )
        }

        // Widget ids in these rows were allocated by another device or install.
        widgetDao.clear()
    }

    private fun appVersionName(): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    private companion object {
        const val TAG = "Backup"
    }
}
