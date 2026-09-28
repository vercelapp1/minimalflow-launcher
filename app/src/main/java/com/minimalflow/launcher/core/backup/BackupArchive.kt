package com.minimalflow.launcher.core.backup

import com.minimalflow.launcher.core.model.AppAlias
import com.minimalflow.launcher.core.model.FavoriteApp
import com.minimalflow.launcher.core.model.HiddenApp
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.ThemeConfig
import kotlinx.serialization.Serializable

/**
 * One launch counter, in a shape the archive can serialise.
 *
 * The repository's own `UsageRecord` is a plain in-memory value; giving the
 * archive its own type keeps the persistence format independent of how the app
 * happens to represent a record today.
 */
@Serializable
data class ArchivedUsage(
    val packageName: String,
    val componentName: String? = null,
    val userSerial: Long = 0L,
    val launchCount: Int,
    val lastLaunchAt: Long,
)

/** A remembered search term. */
@Serializable
data class ArchivedSearchTerm(
    val term: String,
    val lastUsedAt: Long,
    val useCount: Int,
)

/**
 * The whole of a user's MinimalFlow configuration in one JSON document.
 *
 * Widget placements are deliberately absent: they reference host-allocated widget
 * ids that only exist on the device that created them, so restoring them onto
 * another device would produce rows pointing at nothing. The platform rediscovers
 * bound widgets by itself, and `WidgetRepository.reconcile` drops whatever has no
 * row after a restore.
 *
 * Usage counters are included because they are a record of the user's own device
 * and are worth carrying across; search history is included only when the user
 * enabled history in the first place.
 */
@Serializable
data class BackupArchive(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val createdAtEpochMillis: Long,
    val appVersionName: String? = null,
    val configuration: LauncherConfiguration,
    val favorites: List<FavoriteApp> = emptyList(),
    val hiddenApps: List<HiddenApp> = emptyList(),
    val aliases: List<AppAlias> = emptyList(),
    val customThemes: List<ThemeConfig> = emptyList(),
    val usage: List<ArchivedUsage> = emptyList(),
    val searchHistory: List<ArchivedSearchTerm> = emptyList(),
) {
    /** True when this file can be read by the current build. */
    val isReadable: Boolean
        get() = formatVersion in 1..CURRENT_FORMAT_VERSION

    companion object {
        /**
         * Bumped only for changes that older builds cannot read. A field added
         * with a default does *not* need a bump, because
         * `ignoreUnknownKeys` makes old readers tolerate it.
         */
        const val CURRENT_FORMAT_VERSION = 1

        /** Used for the file name the create-document dialog suggests. */
        const val FILE_EXTENSION = "minimalflow.json"
    }
}
