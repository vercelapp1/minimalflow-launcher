package com.minimalflow.launcher.core.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.AppShortcut
import com.minimalflow.launcher.di.Dispatcher
import com.minimalflow.launcher.di.DispatcherKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the static and dynamic shortcuts apps publish.
 *
 * Android only shares shortcuts with the active home app. When MinimalFlow is not
 * the default launcher yet, [hasShortcutAccess] returns `false` and the context
 * menu says so instead of showing an empty list.
 */
@Singleton
class AppShortcutService @Inject constructor(
    @ApplicationContext
    private val context: Context,
    @Dispatcher(DispatcherKind.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val launcherApps: LauncherApps? =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    private val userManager: UserManager? =
        context.getSystemService(Context.USER_SERVICE) as? UserManager

    /** True when the platform will actually give us shortcut data. */
    fun hasShortcutAccess(): Boolean = runCatching {
        launcherApps?.hasShortcutHostPermission() == true
    }.getOrDefault(false)

    /**
     * Loads the shortcuts of every requested app in one pass.
     *
     * Apps that throw are omitted rather than failing the whole request, so one
     * misbehaving app cannot break the shortcut sheet.
     */
    suspend fun loadShortcuts(keys: List<AppKey>): Map<AppKey, List<AppShortcut>> =
        withContext(ioDispatcher) {
            if (keys.isEmpty() || !hasShortcutAccess()) return@withContext emptyMap()

            val result = LinkedHashMap<AppKey, List<AppShortcut>>(keys.size)
            for (key in keys) {
                val shortcuts = loadForApp(key)
                if (shortcuts.isNotEmpty()) result[key] = shortcuts
            }
            result
        }

    /** Why a shortcut could not be loaded, for the error state of the sheet. */
    fun unavailableReason(): String? = if (hasShortcutAccess()) {
        null
    } else {
        "Android only shares app shortcuts with the current home app. Set MinimalFlow as the " +
            "default home app to use them."
    }

    private fun loadForApp(key: AppKey): List<AppShortcut> {
        val apps = launcherApps ?: return emptyList()
        val user = userHandleFor(key.userSerial) ?: return emptyList()

        // A query returns the union of everything matching its flags and there is no
        // public per-shortcut flag saying which match rule fired, so the two
        // categories are separated with one query each. The shortcut sheet is opened
        // deliberately by the user, so the extra round trip is not on a hot path.
        val dynamic = query(apps, user, key, LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        val declared = query(
            apps,
            user,
            key,
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
        )

        val merged = LinkedHashMap<String, ShortcutInfo>()
        for (info in declared) merged[info.id] = info
        for (info in dynamic) merged[info.id] = info

        val dynamicIds = dynamic.mapTo(HashSet()) { it.id }

        return merged.values
            .map { info -> info.toAppShortcut(key, dynamic = info.id in dynamicIds) }
            .sortedWith(compareBy({ it.rank }, { it.shortLabel }))
    }

    private fun query(
        apps: LauncherApps,
        user: UserHandle,
        key: AppKey,
        flags: Int,
    ): List<ShortcutInfo> {
        val query = LauncherApps.ShortcutQuery()
            .setPackage(key.packageName)
            .setQueryFlags(flags)
        return runCatching { apps.getShortcuts(query, user) }
            .onFailure { error ->
                Log.i(TAG, "No shortcuts for ${key.packageName}: ${error.message}")
            }
            .getOrDefault(emptyList())
            .orEmpty()
    }

    private fun ShortcutInfo.toAppShortcut(key: AppKey, dynamic: Boolean): AppShortcut {
        val short = shortLabel?.toString()?.takeIf { it.isNotBlank() } ?: id
        val long = longLabel?.toString()?.takeIf { it.isNotBlank() } ?: short
        return AppShortcut(
            shortcutId = id,
            appKey = key,
            shortLabel = short,
            longLabel = long,
            isEnabled = isEnabled,
            isDynamic = dynamic,
            rank = rank,
        )
    }

    private fun userHandleFor(userSerial: Long): UserHandle? {
        if (userSerial == AppKey.PRIMARY_USER) return Process.myUserHandle()
        return runCatching { userManager?.getUserForSerialNumber(userSerial) }.getOrNull()
    }

    private companion object {
        const val TAG = "AppShortcuts"
    }
}
