package com.minimalflow.launcher.core.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minimalflow.launcher.core.model.GestureConfig
import com.minimalflow.launcher.core.model.GestureSlot
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Recognises the launcher's home screen gestures.
 *
 * Two decisions make this work on a real launcher rather than only in a demo:
 *
 *  1. **It listens on the main pointer pass only.** A child that handles the
 *     touch - a list that scrolls, a row that was tapped, the alphabet rail being
 *     dragged - marks the change consumed, and this detector abandons the gesture
 *     immediately. That is what stops every flick of the list from opening search.
 *  2. **A swipe the list could not consume becomes a gesture.** When the list is
 *     already at the top and the user swipes down, or already at the bottom and
 *     swipes up, the list stops consuming and the gesture fires. No dedicated
 *     "gesture zone" is needed, so the app keeps using the whole screen.
 *
 * Double tap and long press are only recognised when nothing consumed the touch,
 * which makes them mean "the user touched empty space" without any extra
 * bookkeeping.
 */
class GestureDetector(
    private val config: GestureConfig,
    private val edgeZone: Dp = DEFAULT_EDGE_ZONE,
) {

    suspend fun PointerInputScope.detectGestures(onGesture: (GestureDetection) -> Unit) {
        if (!config.hasAnyAction) return

        val edgeZonePx = edgeZone.toPx()
        val singleThresholdPx = thresholdFor(config.sensitivity, min = 20.dp, max = 96.dp).toPx()
        val twoFingerThresholdPx = thresholdFor(config.sensitivity, min = 24.dp, max = 88.dp).toPx()

        var previousTapAt = 0L
        var previousTapPosition = Offset.Zero

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            val startPosition = down.position
            val startEdge = edgeAt(startPosition, edgeZonePx, size.width.toFloat())

            var lastCentroid = startPosition
            var totalMovement = Offset.Zero
            var direction: Direction? = null
            var multiTouch = false
            var consumedByChild = false
            var fired = false
            var waitingForLongPress = true

            while (true) {
                val event = if (waitingForLongPress) {
                    withTimeoutOrNull(LONG_PRESS_TIMEOUT_MILLIS) {
                        awaitPointerEvent(PointerEventPass.Main)
                    }
                } else {
                    awaitPointerEvent(PointerEventPass.Main)
                }

                if (event == null) {
                    // The finger has been still for the whole long-press window.
                    waitingForLongPress = false
                    if (!fired && !consumedByChild) {
                        fired = fire(GestureSlot.LONG_PRESS, startPosition, onGesture)
                    }
                    continue
                }

                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (pressed.any { it.isConsumed }) consumedByChild = true

                if (pressed.size >= 2) {
                    multiTouch = true
                    waitingForLongPress = false
                }

                val centroid = pressed.centroid()
                val delta = centroid - lastCentroid
                lastCentroid = centroid

                if (delta.getDistance() > MOVEMENT_EPSILON_PX) {
                    waitingForLongPress = false
                    totalMovement += delta
                    if (direction == null) direction = directionOf(totalMovement)
                }

                if (consumedByChild) continue

                if (!fired && direction != null) {
                    val thresholdPx = if (multiTouch) twoFingerThresholdPx else singleThresholdPx
                    if (totalMovement.getDistance() >= thresholdPx) {
                        val slot = resolveSlot(direction, startEdge, multiTouch)
                        if (slot != null) fired = fire(slot, startPosition, onGesture)
                    }
                }
            }

            // A touch that nothing consumed, barely moved and was short is a tap
            // on empty space, and may be the first half of a double tap.
            if (!consumedByChild && !fired && !multiTouch &&
                totalMovement.getDistance() < DOUBLE_TAP_SLOP_PX
            ) {
                val now = System.currentTimeMillis()
                val isSecondTap = previousTapAt != 0L &&
                    now - previousTapAt < DOUBLE_TAP_TIMEOUT_MILLIS &&
                    (startPosition - previousTapPosition).getDistance() < DOUBLE_TAP_SLOP_PX

                if (isSecondTap) {
                    previousTapAt = 0L
                    fire(GestureSlot.DOUBLE_TAP, startPosition, onGesture)
                } else {
                    previousTapAt = now
                    previousTapPosition = startPosition
                }
            } else if (consumedByChild || fired) {
                previousTapAt = 0L
            }
        }
    }

    /** Returns true when a command was actually dispatched. */
    private fun fire(
        slot: GestureSlot,
        position: Offset,
        onGesture: (GestureDetection) -> Unit,
    ): Boolean {
        val command = GestureActionMapper.toCommand(config.targetFor(slot))
        if (command is GestureCommand.None) return false
        onGesture(GestureDetection(slot, position, command))
        return true
    }

    private fun resolveSlot(
        direction: Direction,
        startEdge: Edge?,
        multiTouch: Boolean,
    ): GestureSlot? {
        if (multiTouch) {
            return when (direction) {
                Direction.DOWN -> GestureSlot.TWO_FINGER_SWIPE_DOWN
                Direction.LEFT -> GestureSlot.TWO_FINGER_SWIPE_LEFT
                else -> null
            }
        }

        if (startEdge != null) {
            val isInward = when (direction) {
                Direction.RIGHT -> startEdge == Edge.LEFT
                Direction.LEFT -> startEdge == Edge.RIGHT
                else -> false
            }
            if (isInward) {
                return if (startEdge == Edge.LEFT) {
                    GestureSlot.EDGE_LEFT
                } else {
                    GestureSlot.EDGE_RIGHT
                }
            }
        }

        return when (direction) {
            Direction.UP -> GestureSlot.SWIPE_UP
            Direction.DOWN -> GestureSlot.SWIPE_DOWN
            Direction.LEFT -> GestureSlot.SWIPE_LEFT
            Direction.RIGHT -> GestureSlot.SWIPE_RIGHT
        }
    }

    private fun edgeAt(position: Offset, zonePx: Float, widthPx: Float): Edge? = when {
        position.x <= zonePx -> Edge.LEFT
        position.x >= widthPx - zonePx -> Edge.RIGHT
        else -> null
    }

    private fun List<PointerInputChange>.centroid(): Offset {
        var x = 0f
        var y = 0f
        forEach {
            x += it.position.x
            y += it.position.y
        }
        val count = size.coerceAtLeast(1)
        return Offset(x / count, y / count)
    }

    private fun directionOf(offset: Offset): Direction =
        if (abs(offset.x) > abs(offset.y)) {
            if (offset.x > 0) Direction.RIGHT else Direction.LEFT
        } else {
            if (offset.y > 0) Direction.DOWN else Direction.UP
        }

    private enum class Edge { LEFT, RIGHT }

    private enum class Direction { UP, DOWN, LEFT, RIGHT }

    companion object {
        val DEFAULT_EDGE_ZONE: Dp = 20.dp
        const val DOUBLE_TAP_TIMEOUT_MILLIS = 300L
        const val LONG_PRESS_TIMEOUT_MILLIS = 500L

        private const val MOVEMENT_EPSILON_PX = 10f
        private const val DOUBLE_TAP_SLOP_PX = 56f

        /**
         * Converts the 0-100 sensitivity setting into a swipe distance.
         *
         * 0 asks for the longest possible swipe, 100 for the shortest. The value is
         * clamped so a hand-edited configuration cannot make the detector
         * unreachable.
         */
        fun thresholdFor(sensitivity: Int, min: Dp, max: Dp): Dp {
            val fraction = GestureConfig.clampSensitivity(sensitivity) / 100f
            return max + (min - max) * fraction
        }
    }
}

/**
 * Runs [GestureDetector] on this pointer input scope.
 *
 * A member extension needs both receivers, and a call site inside
 * `pointerInput { }` only has the extension receiver to hand. Binding the detector
 * with `run` supplies the other one, which keeps [GestureDetector.detectGestures]
 * a member extension instead of a top-level function.
 */
suspend fun PointerInputScope.detectMinimalFlowGestures(
    config: GestureConfig,
    edgeZone: Dp,
    onGesture: (GestureDetection) -> Unit,
) {
    GestureDetector(config, edgeZone).run { detectGestures(onGesture) }
}

/**
 * Attaches [GestureDetector] to a composable.
 *
 * Attach it to a container that *wraps* the interactive content: the detector
 * then sees every touch and uses the consumption flags of the children to tell
 * "this was a list scroll" apart from "this was a gesture".
 */
fun Modifier.minimalFlowGestures(
    config: GestureConfig,
    edgeZone: Dp = GestureDetector.DEFAULT_EDGE_ZONE,
    onGesture: (GestureDetection) -> Unit,
): Modifier = pointerInput(config, edgeZone) {
    detectMinimalFlowGestures(config, edgeZone, onGesture)
}
