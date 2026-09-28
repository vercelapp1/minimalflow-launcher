package com.minimalflow.launcher.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.minimalflow.launcher.core.model.toComposeColor

/**
 * The eight colours every MinimalFlow screen is allowed to use.
 *
 * Deliberately small. Screens never hard-code a colour; they read from here, so
 * a custom theme is guaranteed to reach every corner of the app. The Material
 * colour scheme is derived from these values and exists only to feed a handful
 * of built-in primitives.
 */
@Immutable
data class MinimalFlowColors(
    /** The window background behind everything. */
    val background: Color,
    /** Cards, sheets, the search field. */
    val surface: Color,
    /** A second surface tone for nested surfaces. */
    val surfaceVariant: Color,
    /** App names, the clock, headings. */
    val primaryText: Color,
    /** Dates, hints, inactive states. */
    val secondaryText: Color,
    /** Selected rows, the search caret, the active theme. */
    val accent: Color,
    /** Hairlines and list separators. */
    val divider: Color,
    /** Failures and destructive confirmations. */
    val error: Color,
    /** Content drawn on top of [accent]. */
    val onAccent: Color,
)

/** Colour scrim used behind bottom sheets. Kept in one place so it can be tuned. */
val MinimalFlowScrim: Color = Color(0x99000000)

val LocalMinimalFlowColors: ProvidableCompositionLocal<MinimalFlowColors> =
    staticCompositionLocalOf {
        error("MinimalFlowColors were requested before MinimalFlowTheme was applied.")
    }

/** The active colour set. */
@Composable
fun minimalFlowColors(): MinimalFlowColors = LocalMinimalFlowColors.current

/**
 * Wraps a colour set so it can be passed straight to `CompositionLocalProvider`.
 *
 * `CompositionLocalProvider` is a composable function, so calling it here would not
 * compile. The `provides` infix is the non-composable half of the same API and
 * returns the [ProvidedValue] directly.
 */
fun provideMinimalFlowColors(colors: MinimalFlowColors): ProvidedValue<MinimalFlowColors> =
    LocalMinimalFlowColors provides colors

/** The same, for callers that would rather not name the type. */
fun minimalFlowColorsProvider(colors: MinimalFlowColors): ProvidedValue<MinimalFlowColors> =
    provideMinimalFlowColors(colors)

/** Builds a colour set from 32-bit ARGB longs, the form themes are stored in. */
fun minimalFlowColorsFromArgb(
    background: Long,
    surface: Long,
    primaryText: Long,
    secondaryText: Long,
    accent: Long,
    divider: Long,
    error: Long = 0xFFE05252L,
): MinimalFlowColors = MinimalFlowColors(
    background = background.toComposeColor(),
    surface = surface.toComposeColor(),
    surfaceVariant = surface.toComposeColor(),
    primaryText = primaryText.toComposeColor(),
    secondaryText = secondaryText.toComposeColor(),
    accent = accent.toComposeColor(),
    divider = divider.toComposeColor(),
    error = error.toComposeColor(),
    onAccent = readableOn(accent),
)

/**
 * Picks black or white - whichever has more contrast - for text drawn on
 * [backgroundArgb]. Uses the same WCAG maths as the theme validator so the
 * theme editor and the runtime agree.
 */
fun readableOn(backgroundArgb: Long): Color {
    val dark = 0xFF101012L
    val light = 0xFFF7F7F8L
    val onDark = com.minimalflow.launcher.core.themes.ThemeValidator.contrast(backgroundArgb, dark)
    val onLight = com.minimalflow.launcher.core.themes.ThemeValidator.contrast(backgroundArgb, light)
    return (if (onDark >= onLight) dark else light).toComposeColor()
}
