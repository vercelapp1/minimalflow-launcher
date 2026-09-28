package com.minimalflow.launcher.core.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.provider.AlarmClock
import android.util.Log
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.AppListEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of a launch attempt. Nothing here is guessed: each case maps to a real API result. */
sealed interface LaunchResult {
    data object Started : LaunchResult
    data object NotInstalled : LaunchResult
    data class Failed(val reason: String) : LaunchResult
}

/** The outcome of starting an uninstall request. */
sealed interface UninstallResult {
    /** The system uninstall screen was opened; the real result arrives via an activity result. */
    data object RequestSent : UninstallResult
    data object AppNotFound : UninstallResult
    data class Failed(val reason: String) : UninstallResult
}

/**
 * Everything the launcher asks the system to *do* with an app.
 *
 * All of it goes through documented APIs: [LauncherApps] for starting activities
 * and shortcuts, `ACTION_VIEW` on `package:` for app details and `ACTION_DELETE`
 * for uninstall. No hidden APIs, no root, no accessibility service.
 */
@Singleton
class AppLaunchService @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {

    private val launcherApps: LauncherApps? =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

    private val packageManager: PackageManager get() = context.packageManager

    /**
     * Starts [entry]'s activity, passing [sourceBounds] so the launched app can
     * animate out of the row the user tapped.
     */
    fun launch(entry: AppListEntry, sourceBounds: Rect? = null): LaunchResult {
        val key = entry.key
        val user = userHandleFor(key.userSerial)
        val component = ComponentName(key.packageName, key.activityClassName ?: key.packageName)

        launcherApps?.let { apps ->
            try {
                apps.startMainActivity(
                    component,
                    user,
                    sourceBounds,
                    Bundle(),
                )
                return LaunchResult.Started
            } catch (error: SecurityException) {
                Log.i(TAG, "startMainActivity denied, falling back to an intent: ${error.message}")
            } catch (error: android.content.ActivityNotFoundException) {
                return LaunchResult.NotInstalled
            } catch (error: IllegalStateException) {
                Log.i(TAG, "startMainActivity failed: ${error.message}")
            }
        }

        return launchViaIntent(component)
    }

    /** Starts a shortcut published by an app, or reports why it cannot. */
    fun startShortcut(key: AppKey, shortcutId: String, sourceBounds: Rect? = null): LaunchResult {
        val apps = launcherApps ?: return LaunchResult.Failed("LauncherApps is unavailable")
        if (!apps.hasShortcutHostPermission()) {
            return LaunchResult.Failed(
                "Shortcuts are only available to the active home app. Set MinimalFlow as default first.",
            )
        }
        return try {
            apps.startShortcut(
                key.packageName,
                shortcutId,
                sourceBounds,
                Bundle(),
                userHandleFor(key.userSerial),
            )
            LaunchResult.Started
        } catch (error: SecurityException) {
            LaunchResult.Failed("The app refused to share that shortcut.")
        } catch (error: android.content.ActivityNotFoundException) {
            LaunchResult.NotInstalled
        } catch (error: IllegalArgumentException) {
            LaunchResult.Failed("That shortcut no longer exists.")
        }
    }

    /** Opens the system "App info" page. */
    fun openAppInfo(key: AppKey): Boolean = startSafely(
        Intent(Intent.ACTION_VIEW)
            .setData(Uri.fromParts("package", key.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /**
     * Opens the system uninstall screen.
     *
     * Android never tells the caller whether the user went through with it from
     * here, so the UI waits for the activity result and refreshes the app list
     * instead of assuming success.
     */
    fun requestUninstall(key: AppKey): UninstallResult {
        if (!isInstalled(key)) return UninstallResult.AppNotFound
        val intent = Intent(Intent.ACTION_DELETE)
            .setData(Uri.fromParts("package", key.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (startSafely(intent)) {
            UninstallResult.RequestSent
        } else {
            UninstallResult.Failed("No app on this device can handle uninstall requests.")
        }
    }

    fun isInstalled(key: AppKey): Boolean = runCatching {
        packageManager.getApplicationInfo(key.packageName, 0)
        true
    }.getOrDefault(false)

    /** Opens whichever clock app the user has installed, if there is one. */
    fun openClockApp(): Boolean {
        val alarm = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafely(alarm)) return true

        val showAlarm = Intent("android.intent.action.SHOW_ALARMS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(showAlarm)
    }

    /** Opens Android's default-app settings so the user can pick a home app. */
    fun openDefaultAppsSettings(): Boolean = startSafely(
        Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** Opens MinimalFlow's own entry in the system app settings. */
    fun openApplicationSettings(): Boolean = startSafely(
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** Opens the system "home app" settings page directly where one exists. */
    fun openHomeSettings(): Boolean {
        val intent = Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(intent)
    }

    private fun launchViaIntent(component: ComponentName): LaunchResult {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return if (startSafely(intent)) LaunchResult.Started else LaunchResult.NotInstalled
    }

    private fun startSafely(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (error: android.content.ActivityNotFoundException) {
        Log.i(TAG, "No activity for ${intent.action}")
        false
    } catch (error: SecurityException) {
        Log.w(TAG, "Not allowed to start ${intent.action}", error)
        false
    }

    private fun userHandleFor(serial: Long): UserHandle {
        if (serial == AppKey.PRIMARY_USER) return Process.myUserHandle()
        val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
        val user = runCatching { userManager?.getUserForSerialNumber(serial) }.getOrNull()
        return user ?: Process.myUserHandle()
    }

    private companion object {
        const val TAG = "AppLaunch"
    }
}
