package com.minimalflow.launcher.core.lifecycle

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.minimalflow.launcher.core.apps.AppDiscoveryService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything about "is this the home app yet?".
 *
 * Three separate questions live here because they behave differently:
 *
 *  * [isDefaultLauncher] is polled, because it changes when the user leaves the app;
 *  * [requestDefaultLauncher] starts a system dialog the user has to confirm, and
 *    there is no callback, so the result is picked up by the next poll;
 *  * [previousHomePackage] is remembered once, on the first launch, so the settings
 *    can offer a one-tap way back to the launcher the user actually came from.
 *
 * A real default is required rather than a convenience shortcut: without it the
 * app cannot receive `ACTION_MAIN`/`CATEGORY_HOME` presses, host widgets, or be
 * chosen as a shortcut target, so the onboarding asks for it up front.
 */
@Singleton
class DefaultHomeCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discoveryService: AppDiscoveryService,
) {

    fun isDefaultLauncher(): Boolean = discoveryService.isDefaultLauncher()

    /**
     * The home package the user had before MinimalFlow, or `null` when MinimalFlow
     * was already the default the first time it ran.
     */
    fun previousHomePackage(): String? = discoveryService.previousHomePackage()

    /**
     * Asks the system to make MinimalFlow the home app.
     *
     * On API 29+ this is a role request, which is the only supported way: the
     * system shows its own dialog and resolves the grant without any callback, so
     * the result is picked up by the next [isDefaultLauncher] poll.
     *
     * On API 26-28 there are no roles, so the home-app settings list is the only
     * route. That is why the fallback exists rather than raising the minimum.
     */
    fun requestDefaultLauncher() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
                ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            null
        } ?: Intent(Settings.ACTION_HOME_SETTINGS)

        startSafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens this app's entry in system settings, used when the dialog is refused. */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startSafely(intent)
    }

    /** Opens the system home-app settings directly. */
    fun openDefaultAppsSettings() {
        val intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startSafely(intent)
    }

    private fun startSafely(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }
}
