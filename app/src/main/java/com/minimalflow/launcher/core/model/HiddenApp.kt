package com.minimalflow.launcher.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * An app the user removed from the visible list.
 *
 * Hiding is a purely local decision: nothing is uninstalled, disabled or shared
 * with anybody. The app stays fully discoverable through search when the user
 * asks for it, and can always be restored from settings.
 */
@Immutable
@Serializable
data class HiddenApp(
    val key: AppKey,
    val hiddenAt: Long = 0L,
)
