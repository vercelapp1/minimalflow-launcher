package com.minimalflow.launcher.core.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.Log
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.InstalledApp
import com.minimalflow.launcher.di.Dispatcher
import com.minimalflow.launcher.di.DispatcherKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the list of launchable activities off the device.
 *
 * [LauncherApps] is used rather than `PackageManager` because it is the API a
 * home app is meant to use: it returns exactly the activities that can be
 * launched, already resolved per user profile, and it does not require the
 * `QUERY_ALL_PACKAGES` permission. Every query runs on [Dispatchers.IO] because
 * resolving labels and application flags touches the package manager and can
 * take tens of milliseconds on a cold cache.
 */
@Singleton
class AppDiscoveryService @Inject constructor(
    @ApplicationContext
    private val context: Context,
    @Dispatcher(DispatcherKind.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val launcherApps: LauncherApps? =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    /**
     * Loads every launchable app visible to this user, including work profile
     * apps where the platform allows it.
     *
     * A failure in one profile is logged and skipped rather than aborting the
     * whole list: a launcher that shows nine out of ten work apps is far more
     * useful than one that shows nothing.
     */
    suspend fun loadInstalledApps(): List<InstalledApp> = withContext(ioDispatcher) {
        val apps = launcherApps ?: return@withContext fallbackViaPackageManager()
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val profiles = runCatching { apps.profiles }.getOrElse { emptyList() }
            .ifEmpty { listOf(Process.myUserHandle()) }

        val result = LinkedHashMap<String, InstalledApp>()
        val installTimes = InstallTimes(context.packageManager)
        for (profile in profiles) {
            val isPrimary = profile == Process.myUserHandle()
            val serial = if (isPrimary) {
                AppKey.PRIMARY_USER
            } else {
                runCatching { userManager?.getSerialNumberForUser(profile) ?: 0L }.getOrDefault(0L)
            }

            val activities = runCatching { apps.getActivityList(null, profile) }
                .onFailure { Log.w(TAG, "Could not read activities for profile $profile", it) }
                .getOrDefault(emptyList())

            for (activity in activities) {
                val component = activity.componentName ?: continue
                if (component.packageName == context.packageName) continue
                val model = activity.toInstalledApp(
                    userSerial = serial,
                    isWorkProfile = !isPrimary,
                    installTimes = installTimes,
                ) ?: continue
                // One entry per component: LauncherApps can legitimately return the
                // same activity more than once across profiles.
                result[model.key.storageKey()] = model
            }
        }
        result.values.toList()
    }

    /** True when this app is currently the user's home application. */
    fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(intent, 0) ?: return false
        return resolved.activityInfo?.packageName == context.packageName
    }

    /**
     * The home package the user had before MinimalFlow, so settings can offer a
     * one-tap "go back" button. `null` when MinimalFlow was already the default.
     */
    fun previousHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())
        val other = candidates
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { it != context.packageName }
        return other
    }

    // --------------------------------------------------------------- internals

    private fun LauncherActivityInfo.toInstalledApp(
        userSerial: Long,
        isWorkProfile: Boolean,
        installTimes: InstallTimes,
    ): InstalledApp? {
        val component = componentName ?: return null
        val label = runCatching { label?.toString().orEmpty() }.getOrDefault("").trim()
        if (label.isEmpty()) return null

        val applicationInfo: ApplicationInfo? = applicationInfo
        val flags = applicationInfo?.flags ?: 0
        val times = installTimes.of(component.packageName)

        return InstalledApp(
            key = AppKey(
                packageName = component.packageName,
                activityClassName = component.className,
                userSerial = userSerial,
            ),
            label = label,
            isSystemApp = flags and ApplicationInfo.FLAG_SYSTEM != 0,
            isUpdatedSystemApp = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            isWorkProfile = isWorkProfile,
            isComponentEnabled = applicationInfo?.enabled ?: true,
            firstInstallTime = times.first,
            lastUpdateTime = times.second,
            badged = false,
        )
    }

    /**
     * Install and update timestamps for a package.
     *
     * `ApplicationInfo` carries neither value, so they come from `PackageInfo`,
     * which is a separate binder round trip per package. A launcher touches a few
     * hundred packages per refresh, so the results are memoised for the length of
     * one load instead of being fetched once per launchable activity.
     */
    private class InstallTimes(private val packageManager: PackageManager) {
        private val cache = HashMap<String, Pair<Long, Long>>()

        fun of(packageName: String): Pair<Long, Long> = cache.getOrPut(packageName) {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0L),
                    )
                }.getOrNull()
            } else {
                @Suppress("DEPRECATION")
                runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
            }
            (info?.firstInstallTime ?: 0L) to (info?.lastUpdateTime ?: 0L)
        }
    }

    /**
     * Used only if [LauncherApps] is unavailable, which should not happen on any
     * supported device. The `<queries>` block in the manifest keeps this
     * equivalent to the LauncherApps result.
     */
    private fun fallbackViaPackageManager(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            context.packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())

        val installTimes = InstallTimes(context.packageManager)
        return resolved.mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            if (activityInfo.packageName == context.packageName) return@mapNotNull null
            val label = runCatching { activityInfo.loadLabel(context.packageManager).toString() }
                .getOrDefault(activityInfo.packageName)
            val times = installTimes.of(activityInfo.packageName)
            InstalledApp(
                key = AppKey(activityInfo.packageName, activityInfo.name, AppKey.PRIMARY_USER),
                label = label,
                isSystemApp = activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isUpdatedSystemApp =
                    activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                isComponentEnabled = activityInfo.enabled,
                firstInstallTime = times.first,
                lastUpdateTime = times.second,
                badged = false,
            )
        }
    }

    private companion object {
        const val TAG = "AppDiscovery"
    }
}
