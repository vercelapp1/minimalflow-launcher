package com.minimalflow.launcher.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations.
 *
 * Version 1 is frozen: it is what a fresh install of MinimalFlow 1.0.0 created.
 * New versions must add a migration here *and* bump
 * [AppDatabase.version]; there are no destructive fallbacks on purpose, because
 * silently wiping somebody's home screen is never the right behaviour for a
 * launcher.
 */
object DatabaseMigrations {

    /**
     * 1 -> 2
     *
     *  * `launcher_settings` gains `extendedJson`, which holds every setting that
     *    does not have a dedicated column. A non-null default of `{}` means
     *    existing rows read back as "no overrides".
     *  * `favorite_apps` moves from a single-column primary key on the package
     *    name to a composite `(packageName, userSerial)` key, so the same app can
     *    be favourited in a work profile and in the personal profile without the
     *    two rows overwriting each other.
     *  * `search_history` and `usage_events` are created. Both start empty, so
     *    nothing has to be migrated into them.
     *  * `widget_placements` gains an index on the provider package because
     *    removal now looks placements up per provider.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE launcher_settings " +
                    "ADD COLUMN extendedJson TEXT NOT NULL DEFAULT '{}'",
            )

            // SQLite cannot widen a primary key in place, so the table is rebuilt.
            // Every column is copied verbatim; only the key changes.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `favorite_apps_new` (
                    `packageName` TEXT NOT NULL,
                    `componentName` TEXT,
                    `userSerial` INTEGER NOT NULL,
                    `displayOrder` INTEGER NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`, `userSerial`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT OR REPLACE INTO `favorite_apps_new` " +
                    "(`packageName`, `componentName`, `userSerial`, `displayOrder`, `addedAt`) " +
                    "SELECT `packageName`, `componentName`, `userSerial`, `displayOrder`, `addedAt` " +
                    "FROM `favorite_apps`",
            )
            db.execSQL("DROP TABLE `favorite_apps`")
            db.execSQL("ALTER TABLE `favorite_apps_new` RENAME TO `favorite_apps`")
            // Dropping the old table dropped its indices with it, including the
            // ordering index the app list sorts by.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_favorite_apps_displayOrder` " +
                    "ON `favorite_apps` (`displayOrder`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `search_history` (
                    `term` TEXT NOT NULL,
                    `lastUsedAt` INTEGER NOT NULL,
                    `useCount` INTEGER NOT NULL,
                    PRIMARY KEY(`term`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `usage_events` (
                    `packageName` TEXT NOT NULL,
                    `componentName` TEXT,
                    `userSerial` INTEGER NOT NULL,
                    `launchCount` INTEGER NOT NULL,
                    `lastLaunchAt` INTEGER NOT NULL,
                    PRIMARY KEY(`packageName`, `userSerial`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_widget_placements_providerPackage` " +
                    "ON `widget_placements` (`providerPackage`)",
            )
        }
    }

    /** Every migration, in order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    /** The schema version the code expects. */
    const val TARGET_VERSION = 2
}
