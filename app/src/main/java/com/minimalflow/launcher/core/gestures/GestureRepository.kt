package com.minimalflow.launcher.core.gestures

import com.minimalflow.launcher.core.data.PreferencesRepository
import com.minimalflow.launcher.core.data.setExtras
import com.minimalflow.launcher.core.model.GestureConfig
import com.minimalflow.launcher.core.model.GestureSlot
import com.minimalflow.launcher.core.model.GestureTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the gesture bindings.
 *
 * The repository is the only place that resolves conflicts, so the settings
 * screen, the on-screen gesture cheat sheet and the detector can never end up
 * with three different views of what a gesture does.
 */
@Singleton
class GestureRepository @Inject constructor(
    private val preferences: PreferencesRepository,
) {

    val config: Flow<GestureConfig> = preferences.configuration
        .map { it.extras.gestureConfig }
        .distinctUntilChanged()

    suspend fun current(): GestureConfig = preferences.currentConfiguration().extras.gestureConfig

    /**
     * Binds [target] to [slot].
     *
     * If another slot in the same finger group already runs the same action, the
     * two are swapped so that neither is silently shadowed. The displaced slot is
     * returned so the caller can tell the user what changed.
     */
    suspend fun bind(slot: GestureSlot, target: GestureTarget): GestureSlot? {
        var displaced: GestureSlot? = null
        preferences.setExtras { extras ->
            val (updated, swapped) = extras.gestureConfig.withResolved(slot, target)
            displaced = swapped
            extras.copy(gestureConfig = updated)
        }
        return displaced
    }

    suspend fun clear(slot: GestureSlot) {
        bind(slot, GestureTarget())
    }

    suspend fun setSensitivity(sensitivity: Int) {
        val clamped = GestureConfig.clampSensitivity(sensitivity)
        preferences.setExtras { it.copy(gestureConfig = it.gestureConfig.copy(sensitivity = clamped)) }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        preferences.setExtras { it.copy(gestureConfig = it.gestureConfig.copy(hapticsEnabled = enabled)) }
    }

    /**
     * Drops bindings that point at apps which are no longer installed.
     *
     * Run on every package change: a gesture that silently does nothing because
     * the target app was uninstalled is worse than a clearly unbound gesture.
     */
    suspend fun pruneMissingApps(isInstalled: (com.minimalflow.launcher.core.model.AppKey) -> Boolean) {
        preferences.setExtras { extras ->
            val config = extras.gestureConfig
            val pruned = config.copy(
                assignments = config.assignments.mapValues { (_, target) ->
                    if (target.appKey != null && !isInstalled(target.appKey)) GestureTarget() else target
                },
            )
            if (pruned == config) extras else extras.copy(gestureConfig = pruned)
        }
    }
}
