package com.minimalflow.launcher.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * An app pinned to the favourites strip at the top of the home screen.
 *
 * [displayOrder] is dense and zero based. The repository rewrites the whole
 * ordering inside a single transaction whenever the user reorders, which keeps
 * the invariant "there is exactly one favourite per order index" easy to reason
 * about and to test.
 */
@Immutable
@Serializable
data class FavoriteApp(
    val key: AppKey,
    val displayOrder: Int,
    val addedAt: Long = 0L,
) {
    fun withOrder(order: Int): FavoriteApp = copy(displayOrder = order)
}

/** A maximum number of favourites the user can pin. */
object FavoriteLimits {
    const val DEFAULT_MAXIMUM = 8
    const val CONFIGURABLE_MAXIMUM = 20

    fun clamp(value: Int): Int = value.coerceIn(1, CONFIGURABLE_MAXIMUM)
}
