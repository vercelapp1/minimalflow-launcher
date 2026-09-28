package com.minimalflow.launcher.core.model

import kotlinx.serialization.Serializable

/**
 * Every physical gesture the home screen understands.
 *
 * The slots are deliberately disjoint: a two-finger swipe can never be confused
 * with a one-finger swipe, and the two screen edges are separate slots. That
 * removes the whole class of "which one wins" bugs at the cost of never letting
 * the user bind the same action to both edge slots *and* both one-finger
 * directions, which [GestureConflictResolver] handles explicitly.
 */
@Serializable
enum class GestureSlot(
    val title: String,
    val description: String,
    val fingerGroup: FingerGroup,
) {
    SWIPE_UP(
        title = "Swipe up",
        description = "Swipe upwards anywhere on the home screen.",
        fingerGroup = FingerGroup.SINGLE_FINGER,
    ),
    SWIPE_DOWN(
        title = "Swipe down",
        description = "Swipe downwards anywhere on the home screen.",
        fingerGroup = FingerGroup.SINGLE_FINGER,
    ),
    SWIPE_RIGHT(
        title = "Swipe right",
        description = "Swipe towards the right edge of the list.",
        fingerGroup = FingerGroup.SINGLE_FINGER,
    ),
    SWIPE_LEFT(
        title = "Swipe left",
        description = "Swipe towards the left edge of the list.",
        fingerGroup = FingerGroup.SINGLE_FINGER,
    ),
    DOUBLE_TAP(
        title = "Double tap",
        description = "Tap the home screen background twice quickly.",
        fingerGroup = FingerGroup.TAP,
    ),
    LONG_PRESS(
        title = "Long press background",
        description = "Press and hold an empty part of the home screen.",
        fingerGroup = FingerGroup.TAP,
    ),
    TWO_FINGER_SWIPE_DOWN(
        title = "Two-finger swipe down",
        description = "Place two fingers and swipe downwards.",
        fingerGroup = FingerGroup.TWO_FINGER,
    ),
    TWO_FINGER_SWIPE_LEFT(
        title = "Two-finger swipe left",
        description = "Place two fingers and swipe towards the left.",
        fingerGroup = FingerGroup.TWO_FINGER,
    ),
    EDGE_LEFT(
        title = "Left edge swipe",
        description = "Swipe inwards starting from the very left edge of the screen.",
        fingerGroup = FingerGroup.EDGE,
    ),
    EDGE_RIGHT(
        title = "Right edge swipe",
        description = "Swipe inwards starting from the very right edge of the screen.",
        fingerGroup = FingerGroup.EDGE,
    ),
    ;

    val isEdge: Boolean get() = fingerGroup == FingerGroup.EDGE

    /** True for slots whose action must run *after* the gesture is released. */
    val directional: Boolean
        get() = this != DOUBLE_TAP && this != LONG_PRESS

    enum class FingerGroup { SINGLE_FINGER, TWO_FINGER, EDGE, TAP }
}

/**
 * What a gesture does.
 *
 * Android deliberately does not expose public APIs for expanding quick settings
 * or locking the device, and MinimalFlow refuses to ask for device
 * administrator or accessibility privileges just to get around that. Those two
 * actions therefore exist in the model so that saved configurations imported
 * from an older backup stay readable, but [isSupported] is `false` and the
 * settings UI never offers them.
 */
@Serializable
enum class GestureAction(
    val title: String,
    val needsAppTarget: Boolean = false,
) {
    NONE("Do nothing"),
    OPEN_SEARCH("Open search"),
    OPEN_APP_LIST("Open the app list"),
    OPEN_SETTINGS("Open settings"),
    OPEN_FAVORITES("Open favourites"),
    OPEN_WIDGET_PANEL("Open the widget panel"),
    LAUNCH_APP("Open a chosen app", needsAppTarget = true),
    OPEN_NOTIFICATION_SHADE("Open the notification shade"),
    OPEN_QUICK_SETTINGS("Open quick settings"),
    LOCK_SCREEN("Lock the screen"),
    ;

    val isSupported: Boolean
        get() = when (this) {
            // No public API exists for either of these.
            OPEN_QUICK_SETTINGS -> false
            LOCK_SCREEN -> false
            else -> true
        }

    /** Shown in the settings screen when a user wonders where an action went. */
    val unavailableReason: String?
        get() = when (this) {
            OPEN_QUICK_SETTINGS ->
                "Android has no public API for expanding quick settings, and MinimalFlow " +
                    "does not use accessibility services to work around that."
            LOCK_SCREEN ->
                "Locking the device requires device administrator access, which MinimalFlow " +
                    "does not request."
            else -> null
        }
}

/** A concrete, serialisable assignment for one [GestureSlot]. */
@Serializable
data class GestureTarget(
    val action: GestureAction = GestureAction.NONE,
    val appKey: AppKey? = null,
) {
    val isNone: Boolean get() = action == GestureAction.NONE

    fun describe(): String = when {
        isNone -> GestureAction.NONE.title
        action.needsAppTarget -> appKey?.let { "Open ${it.packageName}" } ?: action.title
        else -> action.title
    }
}

/** All gesture assignments plus the tuning knobs. */
@Serializable
data class GestureConfig(
    val assignments: Map<GestureSlot, GestureTarget> = defaultAssignments(),
    /**
     * 0 requires a long, deliberate swipe; 100 triggers on a very short flick.
     * Higher really is more sensitive.
     */
    val sensitivity: Int = 50,
    val hapticsEnabled: Boolean = true,
) {
    fun targetFor(slot: GestureSlot): GestureTarget = assignments[slot] ?: GestureTarget()

    /** True when at least one gesture does something, used to skip detection work. */
    val hasAnyAction: Boolean get() = assignments.values.any { !it.isNone }

    fun with(slot: GestureSlot, target: GestureTarget): GestureConfig =
        copy(assignments = assignments + (slot to target))

    /** Applies [GestureConflictResolver] so the UI and the repository cannot disagree. */
    fun withResolved(slot: GestureSlot, target: GestureTarget): Pair<GestureConfig, GestureSlot?> {
        val resolution = GestureConflictResolver.resolve(assignments, slot, target)
        return copy(assignments = resolution.assignments) to resolution.swappedSlot
    }

    companion object {
        const val SENSITIVITY_MIN = 0
        const val SENSITIVITY_MAX = 100

        /**
         * Sensible starting point: swipe up for search, double tap for settings,
         * long press for the app list, nothing else. Edge gestures stay unused so
         * they never fight the system back gesture.
         */
        fun defaultAssignments(): Map<GestureSlot, GestureTarget> = mapOf(
            GestureSlot.SWIPE_UP to GestureTarget(GestureAction.OPEN_SEARCH),
            GestureSlot.DOUBLE_TAP to GestureTarget(GestureAction.OPEN_SETTINGS),
            GestureSlot.LONG_PRESS to GestureTarget(GestureAction.OPEN_APP_LIST),
        )

        fun clampSensitivity(value: Int): Int = value.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
    }
}

/**
 * Keeps one-finger and two-finger bindings from silently shadowing each other.
 *
 * Pure so the behaviour can be unit tested: give it the current map, the slot
 * being edited and the new target, and it returns the map that should be
 * written back plus the slot that was displaced, if any.
 */
object GestureConflictResolver {

    data class Resolution(
        val assignments: Map<GestureSlot, GestureTarget>,
        val swappedSlot: GestureSlot?,
    )

    fun resolve(
        current: Map<GestureSlot, GestureTarget>,
        slot: GestureSlot,
        target: GestureTarget,
    ): Resolution {
        if (target.isNone) {
            return Resolution(current + (slot to GestureTarget()), null)
        }

        val conflicting = current.entries.firstOrNull { (other, existing) ->
            other != slot &&
                other.fingerGroup == slot.fingerGroup &&
                existing.action == target.action &&
                existing.appKey == target.appKey
        } ?: return Resolution(current + (slot to target), null)

        // Swap: the slot the user just edited takes the action, and the slot that
        // used to own it is cleared so the two never fire together.
        return Resolution(
            assignments = current + (slot to target) + (conflicting.key to GestureTarget()),
            swappedSlot = conflicting.key,
        )
    }
}
