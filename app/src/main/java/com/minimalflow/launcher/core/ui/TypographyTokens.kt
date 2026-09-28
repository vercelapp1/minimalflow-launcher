package com.minimalflow.launcher.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.minimalflow.launcher.core.model.TypographyConfig

/**
 * The type scale.
 *
 * The launcher uses a system font by default. No font file is bundled, which
 * keeps the APK small and avoids any licensing question; the monospace variant
 * is offered for people who prefer it and uses the platform's own monospace.
 *
 * Sizes are absolute sp, so the system font-scale setting still multiplies on
 * top of the launcher's own size sliders.
 */
@Immutable
data class MinimalFlowTypography(
    /** The large clock. */
    val clock: TextStyle,
    /** The seconds suffix next to the clock. */
    val clockSeconds: TextStyle,
    /** The date line under the clock. */
    val date: TextStyle,
    /** "Good evening". */
    val greeting: TextStyle,
    /** Temperature and condition. */
    val weather: TextStyle,
    /** App names in the list, favourites and search results. */
    val appLabel: TextStyle,
    /** A–Z group headers. */
    val sectionHeader: TextStyle,
    /** Settings screen titles. */
    val title: TextStyle,
    /** Settings screen subtitles and secondary rows. */
    val subtitle: TextStyle,
    /** Paragraphs, dialog bodies. */
    val body: TextStyle,
    /** Hints, timestamps, badges. */
    val caption: TextStyle,
    /** Button labels. */
    val button: TextStyle,
    /** Alphabet rail letters. */
    val alphabet: TextStyle,
)

val LocalMinimalFlowTypography: ProvidableCompositionLocal<MinimalFlowTypography> =
    staticCompositionLocalOf {
        error("MinimalFlowTypography was requested before MinimalFlowTheme was applied.")
    }

@Composable
fun minimalFlowTypographyTokens(): MinimalFlowTypography = LocalMinimalFlowTypography.current

/**
 * Builds the type scale from user settings.
 *
 * @param config the launcher's own typography settings.
 * @param launcherFontScale the global "text size" multiplier from settings, 0.8
 *   to 1.4. It is applied to every size here; the system font scale is applied
 *   by Compose on top, so a user who has already enlarged text system-wide gets
 *   the sum of both.
 */
fun buildMinimalFlowTypography(
    config: TypographyConfig,
    launcherFontScale: Float,
): MinimalFlowTypography {
    val family = if (config.useMonospace) FontFamily.Monospace else FontFamily.Default
    val letterSpacing = config.letterSpacingEm.em
    val lineHeight = config.lineHeightMultiplier

    fun style(sizeSp: Int, weight: Int): TextStyle = TextStyle(
        fontFamily = family,
        fontSize = scaleSp(sizeSp, launcherFontScale),
        lineHeight = scaleSp(sizeSp, launcherFontScale) * lineHeight,
        fontWeight = FontWeight(weight.coerceIn(100, 900)),
        letterSpacing = letterSpacing,
    )

    return MinimalFlowTypography(
        clock = style(config.clockSizeSp, config.clockWeight),
        clockSeconds = style((config.clockSizeSp * SECONDS_RATIO).toInt(), 300),
        date = style(14, 400),
        greeting = style(20, 400),
        weather = style(14, 500),
        appLabel = style(config.appLabelSizeSp, config.appLabelWeight),
        sectionHeader = style(12, 600),
        title = style(20, 600),
        subtitle = style(14, 400),
        body = style(15, 400),
        caption = style(12, 400),
        button = style(14, 500),
        alphabet = style(11, 500),
    )
}

private const val SECONDS_RATIO = 0.45f

private fun scaleSp(value: Int, scale: Float): TextUnit = (value * scale).sp

/** Weights offered by the clock size/weight settings. */
object ClockWeights {
    val all = listOf(200, 300, 400, 500, 600)
    val labels = mapOf(
        200 to "Light",
        300 to "Regular",
        400 to "Medium",
        500 to "Semibold",
        600 to "Bold",
    )
}
