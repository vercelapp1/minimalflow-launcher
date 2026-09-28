package com.minimalflow.launcher.core.themes

import com.minimalflow.launcher.core.model.CornerRadiusLimits
import com.minimalflow.launcher.core.model.ThemeColors
import com.minimalflow.launcher.core.model.ThemeConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Guards the theme editor against combinations that would be unusable.
 *
 * Two rules matter more than anything else on a home screen:
 *
 *  1. Text has to be readable. A "passes WCAG check" verdict is what stops the
 *     editor from saving a white-on-white theme.
 *  2. A colour has to *exist*. Fully transparent accents make buttons vanish.
 *
 * Pure and deterministic, so the rules can be unit tested instead of being
 * discovered on a phone.
 */
object ThemeValidator {

    /** Result of validating a colour pair. */
    data class ContrastResult(
        val ratio: Double,
        val passesLargeText: Boolean,
        val passesBodyText: Boolean,
    ) {
        val passes: Boolean get() = passesBodyText
    }

    /** Result of validating a whole theme. */
    data class ValidationResult(
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) {
        val isValid: Boolean get() = errors.isEmpty()
        val hasWarnings: Boolean get() = warnings.isNotEmpty()

        fun describe(): String = (errors + warnings).joinToString("\n")
    }

    /** WCAG relative luminance. */
    fun luminance(argb: Long): Double {
        fun channel(value: Int): Double {
            val srgb = value / 255.0
            return if (srgb <= 0.03928) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        val color = argb.toInt()
        val red = channel((color shr 16) and 0xFF)
        val green = channel((color shr 8) and 0xFF)
        val blue = channel(color and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    /** WCAG contrast ratio, always between 1.0 and 21.0. */
    fun contrast(foreground: Long, background: Long): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        val lighter = max(first, second)
        val darker = min(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun checkContrast(foreground: Long, background: Long): ContrastResult {
        val ratio = contrast(foreground, background)
        return ContrastResult(
            ratio = ratio,
            // 3.0:1 is the WCAG floor for text at 18sp+ or bold.
            passesLargeText = ratio >= LARGE_TEXT_MINIMUM,
            // 4.5:1 is the WCAG floor for body text.
            passesBodyText = ratio >= BODY_TEXT_MINIMUM,
        )
    }

    fun validate(theme: ThemeConfig): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (theme.name.isBlank()) errors += "Give the theme a name."
        if (theme.name.length > MAX_NAME_LENGTH) {
            errors += "Keep the name under $MAX_NAME_LENGTH characters."
        }

        validateColor(theme.colors.background, "Background", errors)
        validateColor(theme.colors.surface, "Surface", errors)
        validateColor(theme.colors.primaryText, "Primary text", errors)
        validateColor(theme.colors.secondaryText, "Secondary text", errors)
        validateColor(theme.colors.accent, "Accent", errors)
        validateColor(theme.colors.divider, "Divider", errors)

        if (theme.colors.accent and 0xFF000000L == 0L) {
            errors += "The accent colour is fully transparent, so controls would be invisible."
        }

        val primaryOnBackground = checkContrast(theme.colors.primaryText, theme.colors.background)
        if (!primaryOnBackground.passesBodyText) {
            errors += "Primary text on the background is only " +
                "${formatRatio(primaryOnBackground.ratio)}:1. It needs at least 4.5:1 to be readable."
        } else if (!primaryOnBackground.passesLargeText) {
            warnings += "Primary text on the background is readable for large text only."
        }

        val primaryOnSurface = checkContrast(theme.colors.primaryText, theme.colors.surface)
        if (!primaryOnSurface.passesBodyText) {
            errors += "Primary text on cards is only " +
                "${formatRatio(primaryOnSurface.ratio)}:1. It needs at least 4.5:1."
        }

        val secondaryOnBackground =
            checkContrast(theme.colors.secondaryText, theme.colors.background)
        if (!secondaryOnBackground.passesBodyText) {
            warnings += "Secondary text on the background is only " +
                "${formatRatio(secondaryOnBackground.ratio)}:1, which is hard to read."
        }

        val accentOnBackground = checkContrast(theme.colors.accent, theme.colors.background)
        if (!accentOnBackground.passesLargeText) {
            warnings += "The accent colour barely stands out from the background."
        }

        if (theme.isDark && luminance(theme.colors.background) > DARK_BACKGROUND_MAX_LUMINANCE) {
            warnings += "This is a dark theme with a light background, so the clock and labels " +
                "will use the dark-on-light variant."
        }

        if (theme.cornerRadiusDp !in CornerRadiusLimits.MIN..CornerRadiusLimits.MAX) {
            errors += "Corner radius must be between ${CornerRadiusLimits.MIN} and " +
                "${CornerRadiusLimits.MAX} dp."
        }

        return ValidationResult(errors, warnings)
    }

    /** Convenience: repair the obvious mistakes instead of rejecting the theme. */
    fun autoCorrect(colors: ThemeColors, isDark: Boolean): ThemeColors {
        val text = if (isDark) 0xFFF5F5F5L else 0xFF101010L
        val secondary = if (isDark) 0xFF9A9EA7L else 0xFF5A5A5AL
        val fixedBackground = colors.background.withAlpha(1f)
        return colors.copy(
            background = fixedBackground,
            surface = colors.surface.withAlpha(1f),
            primaryText = readableTextFor(fixedBackground, text),
            secondaryText = readableTextFor(fixedBackground, secondary),
            divider = colors.divider.withAlpha(1f),
        )
    }

    /**
     * Keeps [preferred] when it is readable, otherwise switches to whichever of
     * near-black or near-white contrasts better against [background].
     *
     * Falling back to a fixed white is the obvious shortcut and the wrong one: on
     * a light background white-on-white fails just as badly as the original, so
     * the contrast of both candidates is compared instead.
     */
    private fun readableTextFor(background: Long, preferred: Long): Long {
        if (checkContrast(preferred, background).passesBodyText) return preferred
        val onDark = contrast(FALLBACK_DARK_TEXT, background)
        val onLight = contrast(FALLBACK_LIGHT_TEXT, background)
        return if (onDark >= onLight) FALLBACK_DARK_TEXT else FALLBACK_LIGHT_TEXT
    }

    private fun validateColor(argb: Long, label: String, errors: MutableList<String>) {
        if (argb.toInt() == 0) {
            errors += "$label is not a colour yet."
            return
        }
        if (argb ushr 32 != 0L) {
            errors += "$label has a value that is not a 32-bit ARGB colour."
        }
    }

    private fun Long.withAlpha(alpha: Float): Long =
        (this and 0x00FFFFFFL) or ((alpha.coerceIn(0f, 1f) * 255f).toLong() shl 24)

    private fun formatRatio(ratio: Double): String {
        val rounded = Math.round(ratio * 10.0) / 10.0
        return if (abs(rounded - rounded.toInt()) < 0.05) {
            "${rounded.toInt()}:1"
        } else {
            "$rounded:1"
        }
    }

    const val BODY_TEXT_MINIMUM = 4.5
    const val LARGE_TEXT_MINIMUM = 3.0
    const val MAX_NAME_LENGTH = 40
    private const val DARK_BACKGROUND_MAX_LUMINANCE = 0.35
    private const val FALLBACK_DARK_TEXT = 0xFF101012L
    private const val FALLBACK_LIGHT_TEXT = 0xFFF7F7F8L
}
