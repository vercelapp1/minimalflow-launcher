package com.minimalflow.launcher.core.gestures

import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.GestureAction
import com.minimalflow.launcher.core.model.GestureSlot
import com.minimalflow.launcher.core.model.GestureTarget

/**
 * A resolved gesture: what happened and what the user wants to happen.
 *
 * Detection produces a slot, the slot produces a [GestureTarget] from the saved
 * configuration, and the target becomes a command. Keeping the command separate
 * from the target means the detector never has to know what "open settings"
 * means, and the dispatcher never has to know what a swipe is.
 */
sealed interface GestureCommand {
    data object None : GestureCommand
    data object OpenSearch : GestureCommand
    data object OpenAppList : GestureCommand
    data object OpenSettings : GestureCommand
    data object OpenFavorites : GestureCommand
    data object OpenWidgetPanel : GestureCommand
    data class LaunchApp(val key: AppKey) : GestureCommand
    data object OpenNotificationShade : GestureCommand
}

/** Where and when a gesture fired. */
data class GestureDetection(
    val slot: GestureSlot,
    val position: androidx.compose.ui.geometry.Offset,
    val command: GestureCommand,
) {
    val isActionable: Boolean get() = command !is GestureCommand.None
}

/**
 * Maps a stored [GestureTarget] onto a [GestureCommand].
 *
 * Unsupported actions collapse to [GestureCommand.None] here, which is the last
 * line of defence behind the settings UI: a configuration restored from an older
 * backup that mentions quick settings can never actually try to do it.
 */
object GestureActionMapper {

    fun toCommand(target: GestureTarget): GestureCommand {
        if (!target.action.isSupported) return GestureCommand.None
        return when (target.action) {
            GestureAction.NONE -> GestureCommand.None
            GestureAction.OPEN_SEARCH -> GestureCommand.OpenSearch
            GestureAction.OPEN_APP_LIST -> GestureCommand.OpenAppList
            GestureAction.OPEN_SETTINGS -> GestureCommand.OpenSettings
            GestureAction.OPEN_FAVORITES -> GestureCommand.OpenFavorites
            GestureAction.OPEN_WIDGET_PANEL -> GestureCommand.OpenWidgetPanel
            GestureAction.LAUNCH_APP -> target.appKey
                ?.let { GestureCommand.LaunchApp(it) }
                ?: GestureCommand.None
            GestureAction.OPEN_NOTIFICATION_SHADE -> GestureCommand.OpenNotificationShade
            // Guarded by isSupported above; kept for exhaustiveness.
            GestureAction.OPEN_QUICK_SETTINGS -> GestureCommand.None
            GestureAction.LOCK_SCREEN -> GestureCommand.None
        }
    }

    /** True when the action needs a target app before it can be saved. */
    fun requiresAppTarget(action: GestureAction): Boolean =
        action.isSupported && action.needsAppTarget
}
