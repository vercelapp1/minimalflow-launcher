package com.minimalflow.launcher.core.themes

import com.minimalflow.launcher.core.model.ThemeColors
import com.minimalflow.launcher.core.model.ThemeConfig
import com.minimalflow.launcher.core.model.ThemeIds

/**
 * The themes that ship with the launcher.
 *
 * They are immutable constants, not rows in the database: a fresh install and an
 * install that has been through ten upgrades see exactly the same six themes.
 * Customising is done by duplicating one of these into a custom theme, never by
 * editing a built-in one in place.
 */
object BuiltInThemes {

    val OBSIDIAN = ThemeConfig(
        id = ThemeIds.OBSIDIAN,
        name = "Obsidian",
        colors = ThemeColors(
            background = 0xFF08090BL,
            surface = 0xFF15171BL,
            primaryText = 0xFFF5F5F5L,
            secondaryText = 0xFF999DA6L,
            accent = 0xFFB8D9FFL,
            divider = 0xFF292B30L,
        ),
        isDark = true,
        isBuiltIn = true,
        cornerRadiusDp = 18,
    )

    val PAPER = ThemeConfig(
        id = ThemeIds.PAPER,
        name = "Paper",
        colors = ThemeColors(
            background = 0xFFF7F5F0L,
            surface = 0xFFFFFFFFL,
            primaryText = 0xFF202020L,
            secondaryText = 0xFF6B6B6BL,
            accent = 0xFF526B59L,
            divider = 0xFFE2DED4L,
        ),
        isDark = false,
        isBuiltIn = true,
        cornerRadiusDp = 18,
    )

    val MIDNIGHT_BLUE = ThemeConfig(
        id = ThemeIds.MIDNIGHT_BLUE,
        name = "Midnight Blue",
        colors = ThemeColors(
            background = 0xFF07111FL,
            surface = 0xFF101F33L,
            primaryText = 0xFFEAF2FFL,
            secondaryText = 0xFF8FA6C4L,
            accent = 0xFF7CB8FFL,
            divider = 0xFF1C3049L,
        ),
        isDark = true,
        isBuiltIn = true,
        cornerRadiusDp = 18,
    )

    val FOREST = ThemeConfig(
        id = ThemeIds.FOREST,
        name = "Forest",
        colors = ThemeColors(
            background = 0xFF0C1510L,
            surface = 0xFF17241BL,
            primaryText = 0xFFE8F0E8L,
            secondaryText = 0xFF90A695L,
            accent = 0xFFA6D5A5L,
            divider = 0xFF24362AL,
        ),
        isDark = true,
        isBuiltIn = true,
        cornerRadiusDp = 18,
    )

    val ROSE = ThemeConfig(
        id = ThemeIds.ROSE,
        name = "Rose",
        colors = ThemeColors(
            background = 0xFF1A1015L,
            surface = 0xFF281820L,
            primaryText = 0xFFF8EAF0L,
            secondaryText = 0xFFB99AA8L,
            accent = 0xFFF2A9C5L,
            divider = 0xFF3A2430L,
        ),
        isDark = true,
        isBuiltIn = true,
        cornerRadiusDp = 18,
    )

    val MONOCHROME = ThemeConfig(
        id = ThemeIds.MONOCHROME,
        name = "Monochrome",
        colors = ThemeColors(
            background = 0xFF000000L,
            surface = 0xFF141414L,
            primaryText = 0xFFFFFFFFL,
            secondaryText = 0xFF9A9A9AL,
            accent = 0xFFBDBDBDL,
            divider = 0xFF2A2A2AL,
        ),
        isDark = true,
        isBuiltIn = true,
        cornerRadiusDp = 18,
    )

    val all: List<ThemeConfig> = listOf(OBSIDIAN, PAPER, MIDNIGHT_BLUE, FOREST, ROSE, MONOCHROME)

    val default: ThemeConfig = OBSIDIAN

    private val byId: Map<String, ThemeConfig> = all.associateBy { it.id }

    fun byId(id: String): ThemeConfig? = byId[id]

    fun isBuiltIn(id: String): Boolean = byId.containsKey(id)

    fun indexOf(id: String): Int = all.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /**
     * Creates a copy of [source] under a new custom id, ready to be edited.
     * The name is suffixed so two copies are never confused in the gallery.
     */
    fun duplicate(source: ThemeConfig, newId: String, baseName: String = source.name): ThemeConfig =
        source.copy(
            id = newId,
            name = "$baseName copy",
            isBuiltIn = false,
        )
}
