package com.minimalflow.launcher.core.apps

import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.LauncherConfiguration

/**
 * Decides what the user is allowed to see.
 *
 * Kept as a pure object so the rules can be unit tested without an Android
 * runtime. Hiding never removes anything from the device: it only removes a row
 * from the visible list, and the app can always be restored from settings.
 */
object AppVisibilityPolicy {

    /**
     * A hidden match is satisfied when the stored key points at the whole
     * package or at the exact same activity. Anything more specific in the
     * database than the discovered activity would otherwise silently stop
     * hiding it after an app update renamed its launcher activity.
     */
    fun isHidden(key: AppKey, hiddenKeys: Set<AppKey>): Boolean =
        hiddenKeys.any { it.matches(key) }

    fun isFavorite(key: AppKey, favoriteKeys: Set<AppKey>): Boolean =
        favoriteKeys.any { it.matches(key) }

    /** Applies the "which rows belong on the home screen" rules. */
    fun visibleEntries(
        entries: List<AppListEntry>,
        hiddenKeys: Set<AppKey>,
        configuration: LauncherConfiguration,
    ): List<AppListEntry> = entries.filter { entry ->
        when {
            // Always keep a hidden app's own context-menu action available so the
            // user can restore it without leaving the screen they are on.
            isHidden(entry.key, hiddenKeys) -> false
            entry.app.isWorkProfile && configuration.extras.hideWorkProfileApps -> false
            !entry.app.isComponentEnabled -> false
            else -> true
        }
    }

    /**
     * Search is deliberately more permissive than the list: when the user has
     * asked to hide work profile apps but explicitly searches for one, showing
     * it is the expected behaviour.
     */
    fun searchableEntries(
        entries: List<AppListEntry>,
        hiddenKeys: Set<AppKey>,
        configuration: LauncherConfiguration,
    ): List<AppListEntry> = entries.filter { entry ->
        when {
            isHidden(entry.key, hiddenKeys) && !configuration.extras.searchEnabled -> false
            entry.app.isWorkProfile && configuration.extras.hideWorkProfileApps -> false
            !entry.app.isComponentEnabled -> false
            else -> true
        }
    }

    /** Hidden apps plus every app the user hid that is no longer installed. */
    fun orphanedHiddenKeys(hiddenKeys: Set<AppKey>, installed: Set<AppKey>): Set<AppKey> =
        hiddenKeys.filter { hidden -> installed.none { it.matches(hidden) } }.toSet()
}
