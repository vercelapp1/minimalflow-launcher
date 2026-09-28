package com.minimalflow.launcher.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A local override for the name an app is shown with, plus extra words that
 * should make it easier to find in search.
 *
 * Nothing here leaves the device and no system component is asked to change the
 * real application label.
 */
@Immutable
@Serializable
data class AppAlias(
    val key: AppKey,
    val customLabel: String? = null,
    val searchKeywords: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = cleanedLabel() == null && normalisedKeywords().isEmpty()

    fun cleanedLabel(): String? = customLabel?.trim()?.takeIf { it.isNotEmpty() }

    fun normalisedKeywords(): List<String> =
        searchKeywords.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
}

/** The result of trimming and de-duplicating whatever the user typed. */
data class SanitisedAlias(
    val customLabel: String? = null,
    val searchKeywords: List<String> = emptyList(),
)

object AliasLimits {
    const val MAX_LABEL_LENGTH = 40
    const val MAX_KEYWORDS = 10
    const val MAX_KEYWORD_LENGTH = 30

    /** Trims, length-limits and de-duplicates user input. */
    fun sanitise(customLabel: String?, searchKeywords: List<String>): SanitisedAlias {
        val label = customLabel
            ?.trim()
            ?.take(MAX_LABEL_LENGTH)
            ?.takeIf { it.isNotEmpty() }

        val keywords = searchKeywords.asSequence()
            .map { it.trim().lowercase().take(MAX_KEYWORD_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_KEYWORDS)
            .toList()

        return SanitisedAlias(customLabel = label, searchKeywords = keywords)
    }
}
