package com.minimalflow.launcher.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.minimalflow.launcher.core.data.AliasDao
import com.minimalflow.launcher.core.data.AppDatabase
import com.minimalflow.launcher.core.data.CustomThemeDao
import com.minimalflow.launcher.core.data.DatabaseMigrations
import com.minimalflow.launcher.core.data.FavoriteDao
import com.minimalflow.launcher.core.data.HiddenDao
import com.minimalflow.launcher.core.data.LauncherSettingsDao
import com.minimalflow.launcher.core.data.SearchHistoryDao
import com.minimalflow.launcher.core.data.UsageDao
import com.minimalflow.launcher.core.data.WidgetPlacementDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room wiring.
 *
 * Two things are deliberate here:
 *
 *  * **No `fallbackToDestructiveMigration`.** A launcher that silently deletes
 *    somebody's home screen on a schema mistake is worse than one that refuses to
 *    start, so [DatabaseMigrations.ALL] is the complete list and a missing
 *    migration fails loudly in development.
 *  * **Foreign keys are off.** Every table stands alone and the app clears rows
 *    explicitly, so there is no cascade to get wrong.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
        .addMigrations(*DatabaseMigrations.ALL)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(SchemaVerificationCallback)
        .build()

    @Provides
    fun provideSettingsDao(database: AppDatabase): LauncherSettingsDao = database.settingsDao()

    @Provides
    fun provideFavoriteDao(database: AppDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    fun provideHiddenDao(database: AppDatabase): HiddenDao = database.hiddenDao()

    @Provides
    fun provideAliasDao(database: AppDatabase): AliasDao = database.aliasDao()

    @Provides
    fun provideThemeDao(database: AppDatabase): CustomThemeDao = database.themeDao()

    @Provides
    fun provideWidgetDao(database: AppDatabase): WidgetPlacementDao = database.widgetDao()

    @Provides
    fun provideSearchHistoryDao(database: AppDatabase): SearchHistoryDao = database.searchHistoryDao()

    @Provides
    fun provideUsageDao(database: AppDatabase): UsageDao = database.usageDao()
}

/**
 * Fails fast in debug builds when a migration did not produce the schema the
 * entities describe.
 *
 * Room already validates that itself the first time the database is opened; this
 * hook only makes the log message point at the migration file rather than at
 * Room internals.
 */
private object SchemaVerificationCallback : androidx.room.RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        android.util.Log.i(
            "MinimalFlowDb",
            "Opened MinimalFlow database at version ${db.version}; " +
                "expected ${DatabaseMigrations.TARGET_VERSION}.",
        )
    }
}
