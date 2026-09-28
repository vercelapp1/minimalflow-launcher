package com.minimalflow.launcher.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.minimalflow.launcher.core.model.PrivacyConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A permission-shaped switch the UI can show, whatever actually grants it. */
enum class AppAccess {
    /** `android.permission.PACKAGE_USAGE_STATS`, granted from system settings. */
    USAGE_ACCESS,

    /** `POST_NOTIFICATIONS`, granted with a runtime dialog. */
    NOTIFICATIONS,

    /** `ACCESS_COARSE_LOCATION`, granted with a runtime dialog, weather only. */
    COARSE_LOCATION,
}

/**
 * Reads and requests the handful of permissions MinimalFlow can use.
 *
 * All three are optional and each unlocks exactly one feature, so the launcher
 * works fully without any of them. Two of the three are not runtime permissions in
 * the usual sense - usage access and the default-home role are granted in system
 * settings - which is why this exposes "open the right settings screen" rather than
 * pretending there is a request result to await.
 */
@Singleton
class PermissionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun isGranted(access: AppAccess): Boolean = when (access) {
        AppAccess.USAGE_ACCESS -> hasUsageAccess()
        AppAccess.NOTIFICATIONS -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        AppAccess.COARSE_LOCATION -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * `PACKAGE_USAGE_STATS` is a normal permission the user grants in Settings, so
     * the only honest check is to ask the app-ops service whether *this* app is
     * currently allowed to query usage.
     */
    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The modern call, which does not throw when the caller is not allowed
            // to ask in the first place.
            runCatching {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }.getOrNull()
        } else {
            // API 26-28: the string op has no non-throwing form, so ask for the
            // "introspection" mode, which is the same answer and exists on every
            // supported version.
            runCatching {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }.getOrNull()
        } ?: return false

        // MODE_DEFAULT means "fall back to the permission check", which for a
        // signature permission means the user has not granted it.
        if (mode == AppOpsManager.MODE_DEFAULT) return false
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The screen where [access] can be granted, or `null` when it is already
     * granted or cannot be granted at all.
     */
    fun settingsIntentFor(access: AppAccess): Intent? = when (access) {
        AppAccess.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        AppAccess.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        AppAccess.COARSE_LOCATION -> null
    }

    /** Whether [access] can be requested with an in-app runtime dialog. */
    fun isRuntimePermission(access: AppAccess): Boolean =
        access == AppAccess.NOTIFICATIONS || access == AppAccess.COARSE_LOCATION

    /** The runtime permission string for [access], or `null` for settings-only ones. */
    fun runtimePermission(access: AppAccess): String? = when (access) {
        AppAccess.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
        AppAccess.COARSE_LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
        AppAccess.USAGE_ACCESS -> null
    }

    /**
     * Whether the "most used" and "recently used" sort modes can actually work.
     *
     * Sorting by usage without the permission produces a list ordered by nothing in
     * particular, so the settings screen disables those options instead of showing
     * them and quietly lying.
     */
    fun canTrackUsage(privacy: PrivacyConfig): Boolean =
        privacy.usageTrackingEnabled && isGranted(AppAccess.USAGE_ACCESS)

    /** Whether the weather card may ask for a device location fix. */
    fun canUseDeviceLocation(privacy: PrivacyConfig): Boolean =
        privacy.weatherUseDeviceLocation && isGranted(AppAccess.COARSE_LOCATION)

    fun openUsageAccessSettings() = startSafely(settingsIntentFor(AppAccess.USAGE_ACCESS))

    fun openNotificationSettings() = startSafely(settingsIntentFor(AppAccess.NOTIFICATIONS))

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startSafely(intent)
    }

    /** True when the platform exposes usage statistics at all. */
    fun supportsUsageAccess(): Boolean = context
        .getSystemService(Context.USAGE_STATS_SERVICE) is UsageStatsManager

    private fun startSafely(intent: Intent?) {
        if (intent == null) return
        runCatching { context.startActivity(intent) }
    }
}
