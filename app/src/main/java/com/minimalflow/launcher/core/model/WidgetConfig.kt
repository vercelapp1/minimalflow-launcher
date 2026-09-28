package com.minimalflow.launcher.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Where the widget area sits relative to the app list. */
@Serializable
enum class WidgetPanelPosition {
    ABOVE_LIST,
    BELOW_LIST,
    DISABLED,
}

/**
 * A hosted [android.appwidget.AppWidgetHostView] and where the user put it.
 *
 * [widthSp] and [heightDp] are the *requested* size. Android always has the
 * final say, so the widget repository re-reads the real bounds after every bind
 * and stores what it got.
 */
@Immutable
@Serializable
data class WidgetPlacement(
    val appWidgetId: Int,
    val providerPackage: String,
    val providerClass: String,
    val position: Int = 0,
    val widthSp: Int = DEFAULT_WIDTH_SP,
    val heightDp: Int = DEFAULT_HEIGHT_DP,
    val createdAt: Long = 0L,
    /** Set once the user has resized or moved the widget. */
    val isCustomised: Boolean = false,
) {
    val providerKey: AppKey get() = AppKey(providerPackage, providerClass)

    companion object {
        const val DEFAULT_WIDTH_SP = 180
        const val DEFAULT_HEIGHT_DP = 110
    }
}

/** A widget provider offered by the system picker. */
@Immutable
data class AvailableWidget(
    val providerPackage: String,
    val providerClass: String,
    val label: String,
    val providerDescription: String? = null,
    val minWidthSp: Int = 110,
    val minHeightDp: Int = 40,
    val maxWidthSp: Int = 450,
    val maxHeightDp: Int = 480,
    val previewImageUri: String? = null,
    val isConfigured: Boolean = true,
) {
    val key: AppKey get() = AppKey(providerPackage, providerClass)
}

/** Widget host behaviour. */
@Serializable
data class WidgetConfig(
    val panelPosition: WidgetPanelPosition = WidgetPanelPosition.ABOVE_LIST,
    val spacingDp: Int = 12,
    val resizeEnabled: Boolean = true,
    val editHandlesVisible: Boolean = true,
    val showAddButton: Boolean = true,
) {
    val isEnabled: Boolean get() = panelPosition != WidgetPanelPosition.DISABLED
}

object WidgetLimits {
    val SPACING_MIN = 0
    val SPACING_MAX = 40
    val WIDTH_MIN_SP = 70
    val WIDTH_MAX_SP = 460
    val HEIGHT_MIN_DP = 40
    val HEIGHT_MAX_DP = 520

    fun clampSpacing(value: Int): Int = value.coerceIn(SPACING_MIN, SPACING_MAX)
    fun clampWidth(value: Int): Int = value.coerceIn(WIDTH_MIN_SP, WIDTH_MAX_SP)
    fun clampHeight(value: Int): Int = value.coerceIn(HEIGHT_MIN_DP, HEIGHT_MAX_DP)
}
