package com.minimalflow.launcher.core.themes

import com.minimalflow.launcher.core.model.ThemeColors
import com.minimalflow.launcher.core.model.ThemeConfig
import kotlinx.serialization.json.Json

/**
 * Converts themes to and from plain JSON.
 *
 * Backups and theme sharing both go through the same format, and it is a flat
 * object of ARGB integers so a file written on one device is readable on
 * another. Unknown fields are ignored, which is what makes older builds able to
 * read a newer backup.
 */
object ThemeSerializer {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(theme: ThemeConfig): String = json.encodeToString(ThemeConfig.serializer(), theme)

    fun decode(value: String): ThemeConfig? =
        runCatching { json.decodeFromString(ThemeConfig.serializer(), value) }.getOrNull()

    fun encodeAll(themes: List<ThemeConfig>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ThemeConfig.serializer()), themes)

    fun decodeAll(value: String): List<ThemeConfig> = runCatching {
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ThemeConfig.serializer()),
            value,
        )
    }.getOrDefault(emptyList())

    /** The five colours a theme editor lets the user change, as a colour ramp. */
    fun swatchesFor(colors: ThemeColors): List<Long> = listOf(
        colors.background,
        colors.surface,
        colors.primaryText,
        colors.secondaryText,
        colors.accent,
        colors.divider,
    )
}
