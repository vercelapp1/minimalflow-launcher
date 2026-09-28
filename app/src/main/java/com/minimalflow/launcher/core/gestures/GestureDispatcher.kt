package com.minimalflow.launcher.core.gestures

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.minimalflow.launcher.core.apps.AppLaunchService
import com.minimalflow.launcher.core.apps.AppRepository
import com.minimalflow.launcher.core.apps.LaunchResult
import com.minimalflow.launcher.core.navigation.LauncherRoutes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implemented by whatever is currently on screen so an in-place gesture can stay
 * in place. Everything is optional: when a gesture fires somewhere that cannot
 * host it (a widget, a notification action, the search overlay already open) the
 * dispatcher falls back to opening the settings activity at the right screen.
 */
interface GestureHost {
    fun openSearchOverlay()
    fun openAppList()
    fun openFavorites()
    fun openWidgetPanel()
    fun openSettings()
}

/** What actually happened, so the UI can react (haptics, snackbars, analytics). */
sealed interface GestureOutcome {
    data object Performed : GestureOutcome
    data object HostedElsewhere : GestureOutcome
    data class Unavailable(val reason: String) : GestureOutcome
    data object NoAction : GestureOutcome
}

/**
 * Turns a [GestureCommand] into something the system actually does.
 *
 * The dispatcher owns the "where does this land" question so that neither the
 * detector nor the settings screen has to: the home screen can answer gestures
 * locally, and everything else degrades to a real destination.
 */
@Singleton
class GestureDispatcher @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val appRepository: AppRepository,
    private val launchService: AppLaunchService,
) {

    suspend fun dispatch(command: GestureCommand, host: GestureHost? = null): GestureOutcome =
        when (command) {
            is GestureCommand.None -> GestureOutcome.NoAction

            is GestureCommand.OpenSearch -> when {
                host != null -> {
                    host.openSearchOverlay()
                    GestureOutcome.Performed
                }

                else -> openSettingsActivity(LauncherRoutes.DESTINATION_APPS, search = "")
            }

            is GestureCommand.OpenAppList -> when {
                host != null -> {
                    host.openAppList()
                    GestureOutcome.Performed
                }

                else -> openSettingsActivity(LauncherRoutes.DESTINATION_APPS)
            }

            is GestureCommand.OpenFavorites -> when {
                host != null -> {
                    host.openFavorites()
                    GestureOutcome.Performed
                }

                else -> openSettingsActivity(LauncherRoutes.DESTINATION_HOME)
            }

            is GestureCommand.OpenWidgetPanel -> when {
                host != null -> {
                    host.openWidgetPanel()
                    GestureOutcome.Performed
                }

                else -> openSettingsActivity(LauncherRoutes.DESTINATION_WIDGETS)
            }

            is GestureCommand.OpenSettings -> when {
                host != null -> {
                    host.openSettings()
                    GestureOutcome.Performed
                }

                else -> openSettingsActivity(LauncherRoutes.DESTINATION_HOME)
            }

            is GestureCommand.LaunchApp -> launchApp(command)

            is GestureCommand.OpenNotificationShade -> expandStatusBar()
        }

    private suspend fun launchApp(command: GestureCommand.LaunchApp): GestureOutcome {
        val entry = appRepository.findEntry(command.key)
            ?: return GestureOutcome.Unavailable("That app is no longer installed.")

        appRepository.recordLaunch(command.key)
        return when (val result = launchService.launch(entry)) {
            is LaunchResult.Started -> GestureOutcome.Performed
            is LaunchResult.NotInstalled -> GestureOutcome.Unavailable("That app is no longer installed.")
            is LaunchResult.Failed -> GestureOutcome.Unavailable(result.reason)
        }
    }

    /**
     * Expands the notification shade.
     *
     * This is the documented `ACTION_EXPAND_STATUS_BAR` intent. It is a
     * no-op on builds that do not allow it, and the user is told so rather than
     * being left tapping a gesture that silently does nothing.
     */
    private fun expandStatusBar(): GestureOutcome {
        val intent = Intent("android.intent.action.EXPAND_STATUS_BAR")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (startSafely(intent)) {
            GestureOutcome.Performed
        } else {
            GestureOutcome.Unavailable("This Android build does not let apps open the notification shade.")
        }
    }

    private fun openSettingsActivity(destination: String, search: String? = null): GestureOutcome {
        val intent = Intent().apply {
            setClassName(context, SETTINGS_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(LauncherRoutes.EXTRA_DESTINATION, destination)
            if (search != null) putExtra(LauncherRoutes.EXTRA_SEARCH_QUERY, search)
        }
        return if (startSafely(intent)) {
            GestureOutcome.HostedElsewhere
        } else {
            GestureOutcome.Unavailable("MinimalFlow's settings screen could not be opened.")
        }
    }

    private fun startSafely(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (error: android.content.ActivityNotFoundException) {
        Log.w(TAG, "No activity for ${intent.action}", error)
        false
    } catch (error: SecurityException) {
        Log.w(TAG, "Not allowed to start ${intent.action}", error)
        false
    }

    companion object {
        private const val TAG = "GestureDispatch"

        /**
         * The settings activity, referenced by name.
         *
         * Written as a string on purpose: the dispatcher lives in `core` and must
         * not depend on the `ui` module's classes, and an activity that has been
         * renamed or removed should degrade to "cannot open" instead of crashing.
         */
        private const val SETTINGS_ACTIVITY = "com.minimalflow.launcher.MainActivity"
    }
}

/** Settings entry point reused by the about screen. */
fun Context.openMinimalFlowSettings(destination: String? = null) {
    startActivitySafely(
        Intent().apply {
            setClassName(this@openMinimalFlowSettings, "com.minimalflow.launcher.MainActivity")
            if (destination != null) putExtra(LauncherRoutes.EXTRA_DESTINATION, destination)
        },
    )
}

/** Opens the system screen that lists installed apps, used by the about screen. */
fun Context.openSystemAppSettings() {
    startActivitySafely(Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS))
}

/** Opens the system default-apps screen so the user can pick a home app. */
fun Context.openDefaultAppsSettings() {
    startActivitySafely(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
}

private fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: android.content.ActivityNotFoundException) {
        Log.w("MinimalFlow", "No activity for ${intent.action}", error)
    }
}
