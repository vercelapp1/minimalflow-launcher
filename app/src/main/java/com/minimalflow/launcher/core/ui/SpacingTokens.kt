package com.minimalflow.launcher.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing, radii and touch-target rules.
 *
 * A 4 dp base grid keeps the rhythm consistent without a lot of magic numbers,
 * and the minimum sizes here are what keep every control reachable one-handed on
 * a large phone.
 */
object Spacing {
    val none: Dp = 0.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val huge: Dp = 48.dp

    /** Outer padding applied to full-screen settings content. */
    val screen: Dp = 20.dp

    /** Horizontal rhythm inside a list row. */
    val rowHorizontal: Dp = 20.dp

    /** Gap between the favourites strip and the app list. */
    val sectionGap: Dp = 20.dp
}

/**
 * Corner radius, driven by the active theme.
 *
 * Themes set this, so a user who wants sharp rectangles gets them everywhere at
 * once. Defaults to 18 dp, which is the shape of the default Obsidian theme.
 */
val LocalCornerRadius: ProvidableCompositionLocal<Dp> = staticCompositionLocalOf { 18.dp }

@Composable
fun cornerRadius(): Dp = LocalCornerRadius.current

/** Extra radii derived from the theme radius, for chips and inner corners. */
@Composable
fun cornerRadiusSmall(): Dp = (LocalCornerRadius.current * 0.5f).coerceAtLeast(0.dp)

@Composable
fun cornerRadiusLarge(): Dp = (LocalCornerRadius.current * 1.5f).coerceAtLeast(0.dp)

/**
 * Minimum interactive size.
 *
 * 48 dp is the accessibility guideline; the launcher never goes below it, even
 * when the row height slider is turned all the way down, so no control can
 * become impossible to hit.
 */
object TouchTarget {
    val minimum: Dp = 48.dp
    val comfortable: Dp = 56.dp
    val iconButton: Dp = 48.dp
}

/** Standard padding for the content of a settings screen. */
@Composable
fun screenPadding(extraBottom: Dp = Spacing.xl): PaddingValues = PaddingValues(
    start = Spacing.screen,
    end = Spacing.screen,
    top = Spacing.lg,
    bottom = extraBottom,
)
