package com.minimalflow.launcher.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room schema for MinimalFlow.
 *
 * Everything here is local configuration. There is no remote data source, no
 * account and no synchronisation, which is why the whole database fits in a
 * couple of dozen small tables.
 *
 * Schema history:
 *  * v1 – settings, favourites, hidden apps, aliases, custom themes, widgets.
 *         `favorite_apps` was keyed on the package name alone.
 *  * v2 – adds the JSON `extendedJson` settings column, search history and the
 *         local usage counters, plus indices for widget lookups. `favorite_apps`
 *         is re-keyed on `(packageName, userSerial)` so work profile apps can be
 *         favourited independently of their personal-profile twin.
 */
@Database(
    entities = [
        LauncherSettingsEntity::class,
        FavoriteAppEntity::class,
        HiddenAppEntity::class,
        AppAliasEntity::class,
        CustomThemeEntity::class,
        WidgetPlacementEntity::class,
        SearchHistoryEntity::class,
        UsageEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun settingsDao(): LauncherSettingsDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun hiddenDao(): HiddenDao
    abstract fun aliasDao(): AliasDao
    abstract fun themeDao(): CustomThemeDao
    abstract fun widgetDao(): WidgetPlacementDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun usageDao(): UsageDao

    companion object {
        const val DATABASE_NAME = "minimalflow.db"
    }
}

/**
 * Single-row table holding the primary settings. `id` is pinned to [SINGLETON_ID]
 * so the table can never grow.
 */
@Entity(tableName = "launcher_settings")
data class LauncherSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val themeMode: String,
    val themeId: String,
    val accentColor: Long?,
    val backgroundColor: Long?,
    val fontSize: Int,
    val iconSize: Int,
    val rowHeight: Int,
    val sortingMode: String,
    val showClock: Boolean,
    val showDate: Boolean,
    val showWeather: Boolean,
    val showFavorites: Boolean,
    val showIcons: Boolean,
    val showLabels: Boolean,
    val showAlphabetRail: Boolean,
    val animationEnabled: Boolean,
    /** Serialised [LauncherConfigurationExtras]; `{}` for a default install. */
    val extendedJson: String = "{}",
) {
    companion object {
        const val SINGLETON_ID = 0
        const val TABLE = "launcher_settings"
    }
}

@Dao
interface LauncherSettingsDao {

    @Query("SELECT * FROM launcher_settings WHERE id = 0")
    fun observe(): Flow<LauncherSettingsEntity?>

    @Query("SELECT * FROM launcher_settings WHERE id = 0")
    suspend fun get(): LauncherSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LauncherSettingsEntity)
}

@Entity(
    tableName = "favorite_apps",
    primaryKeys = ["packageName", "userSerial"],
    indices = [Index("displayOrder")],
)
data class FavoriteAppEntity(
    val packageName: String,
    val componentName: String?,
    val userSerial: Long,
    val displayOrder: Int,
    val addedAt: Long,
)

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorite_apps ORDER BY displayOrder ASC")
    fun observeAll(): Flow<List<FavoriteAppEntity>>

    @Query("SELECT * FROM favorite_apps ORDER BY displayOrder ASC")
    suspend fun getAll(): List<FavoriteAppEntity>

    @Query("SELECT * FROM favorite_apps WHERE packageName = :packageName AND userSerial = :userSerial LIMIT 1")
    suspend fun find(packageName: String, userSerial: Long): FavoriteAppEntity?

    @Query("SELECT COALESCE(MAX(displayOrder), -1) FROM favorite_apps")
    suspend fun maxOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FavoriteAppEntity>)

    @Query("DELETE FROM favorite_apps WHERE packageName = :packageName AND userSerial = :userSerial")
    suspend fun delete(packageName: String, userSerial: Long)

    @Query("DELETE FROM favorite_apps")
    suspend fun clear()

    /**
     * Rewrites the entire ordering in one transaction so the indices stay dense
     * even if a previous reorder was interrupted half way through.
     */
    @Transaction
    suspend fun replaceAll(orderedKeys: List<FavoriteAppEntity>) {
        clear()
        upsertAll(orderedKeys.mapIndexed { index, entity -> entity.copy(displayOrder = index) })
    }
}

@Entity(
    tableName = "hidden_apps",
    primaryKeys = ["packageName", "userSerial"],
)
data class HiddenAppEntity(
    val packageName: String,
    val componentName: String?,
    val userSerial: Long,
    val hiddenAt: Long,
)

@Dao
interface HiddenDao {

    @Query("SELECT * FROM hidden_apps")
    fun observeAll(): Flow<List<HiddenAppEntity>>

    @Query("SELECT * FROM hidden_apps")
    suspend fun getAll(): List<HiddenAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HiddenAppEntity)

    @Query("DELETE FROM hidden_apps WHERE packageName = :packageName AND userSerial = :userSerial")
    suspend fun delete(packageName: String, userSerial: Long)

    @Query("DELETE FROM hidden_apps")
    suspend fun clear()
}

@Entity(
    tableName = "app_aliases",
    primaryKeys = ["packageName", "userSerial"],
)
data class AppAliasEntity(
    val packageName: String,
    val componentName: String?,
    val userSerial: Long,
    val customLabel: String?,
    /** Comma separated, already normalised to lower case. */
    val searchKeywords: String,
    val updatedAt: Long,
)

@Dao
interface AliasDao {

    @Query("SELECT * FROM app_aliases")
    fun observeAll(): Flow<List<AppAliasEntity>>

    @Query("SELECT * FROM app_aliases")
    suspend fun getAll(): List<AppAliasEntity>

    @Query("SELECT * FROM app_aliases WHERE packageName = :packageName AND userSerial = :userSerial LIMIT 1")
    suspend fun find(packageName: String, userSerial: Long): AppAliasEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppAliasEntity)

    @Query("DELETE FROM app_aliases WHERE packageName = :packageName AND userSerial = :userSerial")
    suspend fun delete(packageName: String, userSerial: Long)

    @Query("DELETE FROM app_aliases")
    suspend fun clear()
}

@Entity(tableName = "custom_themes")
data class CustomThemeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val backgroundColor: Long,
    val surfaceColor: Long,
    val primaryTextColor: Long,
    val secondaryTextColor: Long,
    val accentColor: Long,
    val dividerColor: Long,
    val errorColor: Long,
    val fontFamily: String,
    val fontSize: Int,
    val iconSize: Int,
    val rowHeight: Int,
    val isDark: Boolean,
    val cornerRadiusDp: Int,
    val createdAt: Long,
)

@Dao
interface CustomThemeDao {

    @Query("SELECT * FROM custom_themes ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CustomThemeEntity>>

    @Query("SELECT * FROM custom_themes ORDER BY createdAt ASC")
    suspend fun getAll(): List<CustomThemeEntity>

    @Query("SELECT * FROM custom_themes WHERE id = :id LIMIT 1")
    suspend fun find(id: String): CustomThemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CustomThemeEntity)

    @Update
    suspend fun update(entity: CustomThemeEntity)

    @Query("DELETE FROM custom_themes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM custom_themes")
    suspend fun clear()
}

@Entity(
    tableName = "widget_placements",
    indices = [Index("providerPackage")],
)
data class WidgetPlacementEntity(
    @PrimaryKey val appWidgetId: Int,
    val providerPackage: String,
    val providerClass: String,
    val position: Int,
    val widthSp: Int,
    val heightDp: Int,
    val createdAt: Long,
    val isCustomised: Boolean,
)

@Dao
interface WidgetPlacementDao {

    @Query("SELECT * FROM widget_placements ORDER BY position ASC, appWidgetId ASC")
    fun observeAll(): Flow<List<WidgetPlacementEntity>>

    @Query("SELECT * FROM widget_placements ORDER BY position ASC, appWidgetId ASC")
    suspend fun getAll(): List<WidgetPlacementEntity>

    @Query("SELECT * FROM widget_placements WHERE appWidgetId = :id LIMIT 1")
    suspend fun find(id: Int): WidgetPlacementEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WidgetPlacementEntity)

    @Query("DELETE FROM widget_placements WHERE appWidgetId = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM widget_placements")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(entities: List<WidgetPlacementEntity>) {
        clear()
        entities.forEachIndexed { index, entity -> upsert(entity.copy(position = index)) }
    }
}

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val term: String,
    val lastUsedAt: Long,
    val useCount: Int,
)

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC")
    suspend fun getAll(): List<SearchHistoryEntity>

    @Query("SELECT * FROM search_history WHERE term = :term LIMIT 1")
    suspend fun find(term: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE term = :term")
    suspend fun delete(term: String)

    /** Keeps the newest [limit] rows and removes the rest. */
    @Query(
        "DELETE FROM search_history WHERE term NOT IN " +
            "(SELECT term FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit)",
    )
    suspend fun deleteOutsideNewest(limit: Int)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Entity(
    tableName = "usage_events",
    primaryKeys = ["packageName", "userSerial"],
)
data class UsageEventEntity(
    val packageName: String,
    val componentName: String?,
    val userSerial: Long,
    val launchCount: Int,
    val lastLaunchAt: Long,
)

@Dao
interface UsageDao {

    @Query("SELECT * FROM usage_events")
    fun observeAll(): Flow<List<UsageEventEntity>>

    @Query("SELECT * FROM usage_events")
    suspend fun getAll(): List<UsageEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UsageEventEntity)

    @Query("DELETE FROM usage_events")
    suspend fun clear()
}
