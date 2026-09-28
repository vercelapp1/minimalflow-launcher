package com.minimalflow.launcher.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Light / dark preference. */
@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        val DEFAULT = DARK
    }
}

/** How the home screen background is produced. */
@Serializable
enum class WallpaperMode {
    /** Flat colour taken from the active theme. */
    THEME_COLOR,
    /** The current system wallpaper, dimmed. */
    SYSTEM_WALLPAPER,
    /** A single colour the user picked. */
    SOLID_COLOR,
    /** A vertical two-stop gradient. */
    GRADIENT,
    /** A picture the user picked through the system document picker. */
    LOCAL_IMAGE,
}

/** Where the favourites strip sits and how dense it is. */
@Serializable
enum class FavoritesLayout {
    /** Icons only, in a single tight row. */
    COMPACT,
    /** Icons with labels underneath, more padding. */
    SPACIOUS,
    /** One app per line, same rhythm as the main list. */
    LIST,
}

/** Which edge the alphabet rail hugs. */
@Serializable
enum class RailPosition {
    START,
    END,
    HIDDEN,
}

/** Celsius or Fahrenheit. */
@Serializable
enum class TemperatureUnit {
    CELSIUS,
    FAHRENHEIT;

    val symbol: String get() = if (this == CELSIUS) "\u00B0C" else "\u00B0F"
}

/**
 * The colours a theme is made of. Stored as 32-bit ARGB longs rather than
 * `Color` so that the whole configuration is trivially serialisable.
 */
@Serializable
data class ThemeColors(
    val background: Long = 0xFF08090BL,
    val surface: Long = 0xFF15171BL,
    val primaryText: Long = 0xFFF5F5F5L,
    val secondaryText: Long = 0xFF999DA6L,
    val accent: Long = 0xFFB8D9FFL,
    val divider: Long = 0xFF292B30L,
    val error: Long = 0xFFE05252L,
) {
    fun withBackground(value: Long) = copy(background = value)
    fun withSurface(value: Long) = copy(surface = value)
    fun withPrimaryText(value: Long) = copy(primaryText = value)
    fun withSecondaryText(value: Long) = copy(secondaryText = value)
    fun withAccent(value: Long) = copy(accent = value)
    fun withDivider(value: Long) = copy(divider = value)
}

/**
 * A complete, self-contained theme.
 *
 * Built-in themes have a stable [id] and can never be deleted or renamed; they
 * can be duplicated into an editable custom theme instead.
 */
@Immutable
@Serializable
data class ThemeConfig(
    val id: String,
    val name: String,
    val colors: ThemeColors = ThemeColors(),
    val isDark: Boolean = true,
    val isBuiltIn: Boolean = false,
    val cornerRadiusDp: Int = 18,
) {
    companion object {
        const val MIN_CORNER_RADIUS = 0
        const val MAX_CORNER_RADIUS = 32
    }
}

/** Per-theme adjustable shape, kept separate so the editor can validate it. */
object CornerRadiusLimits {
    const val MIN = 0
    const val MAX = 32
    const val DEFAULT = 18

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

/** Wallpaper configuration. */
@Serializable
data class WallpaperConfig(
    val mode: WallpaperMode = WallpaperMode.THEME_COLOR,
    val imageUri: String? = null,
    val solidColor: Long? = null,
    val gradientFrom: Long? = null,
    val gradientTo: Long? = null,
    /** 0f = untouched, 1f = black. Applied to photos and system wallpapers. */
    val dimAmount: Float = 0f,
    /** True blacks out photo areas on OLED panels to save power. */
    val amoledBlack: Boolean = false,
    /** Blur is only honoured on API 31+; ignored silently below that. */
    val blurEnabled: Boolean = false,
    val blurRadiusDp: Int = 20,
)

/** Type scale, expressed in absolute sp so it composes with system font scaling. */
@Serializable
data class TypographyConfig(
    /** Multiplies the app-label size, 80..140. */
    val fontScalePercent: Int = 100,
    val appLabelSizeSp: Int = 15,
    val clockSizeSp: Int = 54,
    val clockWeight: Int = 300,
    val appLabelWeight: Int = 400,
    val letterSpacingEm: Float = 0f,
    val lineHeightMultiplier: Float = 1.25f,
    val useMonospace: Boolean = false,
)

/** Icon appearance, icon pack selection and per-app overrides. */
@Serializable
data class IconConfig(
    val showIcons: Boolean = true,
    val sizeDp: Int = 46,
    val packPackage: String? = null,
    val packLabel: String? = null,
    /** [AppKey.storageKey] to either an image URI or a `pack/class` reference. */
    val perAppOverrides: Map<String, String> = emptyMap(),
)

/** Optional weather module configuration. */
@Serializable
data class WeatherConfig(
    val enabled: Boolean = false,
    val useDeviceLocation: Boolean = false,
    val cityName: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
) {
    /** Weather can only be fetched when a location is actually known. */
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

/** Companion settings for the [com.minimalflow.launcher.core.model.WallpaperConfig]. */
object WallpaperLimits {
    val DIM_MIN = 0f
    val DIM_MAX = 0.9f
    val BLUR_MIN = 0
    val BLUR_MAX = 50

    fun clampDim(value: Float): Float = value.coerceIn(DIM_MIN, DIM_MAX)
    fun clampBlur(value: Int): Int = value.coerceIn(BLUR_MIN, BLUR_MAX)
}

/** Bounds for the type scale. */
object TypographyLimits {
    val FONT_SCALE_MIN = 80
    val FONT_SCALE_MAX = 140
    val LABEL_SIZE_MIN = 10
    val LABEL_SIZE_MAX = 24
    val CLOCK_SIZE_MIN = 28
    val CLOCK_SIZE_MAX = 96
    val LETTER_SPACING_MIN = -0.05f
    val LETTER_SPACING_MAX = 0.25f
    val LINE_HEIGHT_MIN = 1.0f
    val LINE_HEIGHT_MAX = 1.8f

    fun clampFontScale(value: Int): Int = value.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
    fun clampLabelSize(value: Int): Int = value.coerceIn(LABEL_SIZE_MIN, LABEL_SIZE_MAX)
    fun clampClockSize(value: Int): Int = value.coerceIn(CLOCK_SIZE_MIN, CLOCK_SIZE_MAX)
    fun clampLetterSpacing(value: Float): Float = value.coerceIn(LETTER_SPACING_MIN, LETTER_SPACING_MAX)
    fun clampLineHeight(value: Float): Float = value.coerceIn(LINE_HEIGHT_MIN, LINE_HEIGHT_MAX)
}

/** Bounds for icon appearance. */
object IconLimits {
    val SIZE_MIN = 24
    val SIZE_MAX = 96
    val DEFAULT_SIZE = 46

    fun clampSize(value: Int): Int = value.coerceIn(SIZE_MIN, SIZE_MAX)
}
