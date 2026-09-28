package com.minimalflow.launcher.core.themes

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.minimalflow.launcher.core.model.AnimationLimits
import com.minimalflow.launcher.core.model.ColorMode
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.ThemeConfig
import com.minimalflow.launcher.core.ui.LocalCornerRadius
import com.minimalflow.launcher.core.ui.MinimalFlowColors
import com.minimalflow.launcher.core.ui.buildMinimalFlowTypography
import com.minimalflow.launcher.core.ui.minimalFlowColorsFromArgb
import com.minimalflow.launcher.core.ui.provideMinimalFlowColors
import com.minimalflow.launcher.core.ui.LocalMinimalFlowTypography
import androidx.compose.ui.unit.dp

/**
 * Resolves a theme plus the settings that override it into the colour set the
 * UI actually paints with.
 *
 * A plain object with no Android dependencies, so both the composable theme and
 * non-Compose callers (for example deciding whether the status bar icons should
 * be light) use exactly the same rules.
 */
object ThemeManager {

    private const val AMOLED_BLACK = 0xFF000000L

    /**
     * Colours in use, in this order:
     *  1. the active theme,
     *  2. the optional accent and background overrides from settings,
     *  3. AMOLED black, which replaces the background when enabled.
     */
    fun colorsFor(theme: ThemeConfig, configuration: LauncherConfiguration): MinimalFlowColors {
        val base = theme.colors
        val background = if (configuration.wallpaper.amoledBlack) {
            AMOLED_BLACK
        } else {
            configuration.backgroundColor ?: base.background
        }
        return minimalFlowColorsFromArgb(
            background = background,
            surface = base.surface,
            primaryText = base.primaryText,
            secondaryText = base.secondaryText,
            accent = configuration.accentColor ?: base.accent,
            divider = base.divider,
            error = base.error,
        )
    }

    /** True when the palette is light enough to need dark status bar icons. */
    fun usesDarkStatusBarIcons(colors: MinimalFlowColors): Boolean =
        ThemeValidator.luminance(colors.background.value.toLong()) > STATUS_BAR_LUMINANCE_THRESHOLD

    fun isDark(theme: ThemeConfig, mode: ColorMode, systemInDark: Boolean): Boolean = when (mode) {
        ColorMode.DARK -> true
        ColorMode.LIGHT -> false
        ColorMode.SYSTEM -> systemInDark
    }

    private const val STATUS_BAR_LUMINANCE_THRESHOLD = 0.4
}

/**
 * Applies the MinimalFlow design system.
 *
 * Every colour animates across when the theme changes, unless the user turned
 * animations off, in which case the switch is instant. Both the MinimalFlow
 * colour set and the type scale are published through composition locals so no
 * screen has to thread them through its parameters.
 */
@Composable
fun MinimalFlowTheme(
    theme: ThemeConfig = BuiltInThemes.default,
    configuration: LauncherConfiguration = LauncherConfiguration(),
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val dark = ThemeManager.isDark(theme, configuration.themeMode, systemInDark)
    val target = ThemeManager.colorsFor(theme, configuration)

    val animationMillis = if (configuration.animationEnabled) {
        AnimationLimits.duration(BASE_THEME_ANIMATION_MILLIS, configuration.extras.animationSpeedPercent)
    } else {
        0
    }
    val colorTween = tween<Color>(durationMillis = animationMillis)

    val background by animateColorAsState(target.background, colorTween, label = "background")
    val surface by animateColorAsState(target.surface, colorTween, label = "surface")
    val primaryText by animateColorAsState(target.primaryText, colorTween, label = "primaryText")
    val secondaryText by animateColorAsState(target.secondaryText, colorTween, label = "secondaryText")
    val accent by animateColorAsState(target.accent, colorTween, label = "accent")
    val divider by animateColorAsState(target.divider, colorTween, label = "divider")
    val error by animateColorAsState(target.error, colorTween, label = "error")
    val onAccent by animateColorAsState(target.onAccent, colorTween, label = "onAccent")

    val colors = MinimalFlowColors(
        background = background,
        surface = surface,
        surfaceVariant = surface,
        primaryText = primaryText,
        secondaryText = secondaryText,
        accent = accent,
        divider = divider,
        error = error,
        onAccent = onAccent,
    )

    val typography = remember(configuration.typography, configuration.fontSize) {
        buildMinimalFlowTypography(configuration.typography, configuration.fontScale)
    }

    // A Material scheme is still provided because a few primitives (ripples,
    // sliders, switches) read from it. It is derived, never the source.
    val materialColors = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = background,
            onBackground = primaryText,
            surface = surface,
            onSurface = primaryText,
            surfaceVariant = surface,
            onSurfaceVariant = secondaryText,
            outline = divider,
            error = error,
            onError = onAccent,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            background = background,
            onBackground = primaryText,
            surface = surface,
            onSurface = primaryText,
            surfaceVariant = surface,
            onSurfaceVariant = secondaryText,
            outline = divider,
            error = error,
            onError = onAccent,
        )
    }

    CompositionLocalProvider(
        provideMinimalFlowColors(colors),
        LocalMinimalFlowTypography provides typography,
        LocalCornerRadius provides theme.cornerRadiusDp.dp,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            content = content,
        )
    }
}

private const val BASE_THEME_ANIMATION_MILLIS = 260
