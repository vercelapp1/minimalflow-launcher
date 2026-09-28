package com.minimalflow.launcher.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** How the app list is ordered. */
@Serializable
enum class SortMode(val title: String) {
    ALPHABETICAL("Alphabetical (A–Z)"),
    ALPHABETICAL_REVERSE("Alphabetical (Z–A)"),
    RECENT("Recently used"),
    FREQUENT("Most used"),
    ;

    companion object {
        val DEFAULT = ALPHABETICAL
    }
}

/** Local-only privacy switches. Nothing here ever leaves the device. */
@Serializable
data class PrivacyConfig(
    /** Records launch counts and timestamps so "most used" sorting works. */
    val usageTrackingEnabled: Boolean = false,
    /** Keeps the last few search terms and offers to clear them. */
    val searchHistoryEnabled: Boolean = false,
    /** Asks for a coarse location fix, but only for the weather widget. */
    val weatherUseDeviceLocation: Boolean = false,
    /** Maximum number of remembered search terms, 0 disables history entirely. */
    val searchHistoryLimit: Int = 20,
) {
    companion object {
        const val HISTORY_LIMIT_MIN = 0
        const val HISTORY_LIMIT_MAX = 50

        fun clampHistoryLimit(value: Int): Int = value.coerceIn(HISTORY_LIMIT_MIN, HISTORY_LIMIT_MAX)
    }
}

/**
 * Every user-facing setting in one immutable value.
 *
 * The first block of properties maps one-to-one onto dedicated columns of the
 * `LauncherSettings` Room table, because those are the settings the database
 * schema calls out explicitly. Everything else lives in [extras], which is
 * serialised into a single JSON column so new settings can be added without a
 * migration. [toExtras] and [LauncherConfigurationExtras.toConfiguration] are
 * pure and covered by unit tests.
 */
@Immutable
@Serializable
data class LauncherConfiguration(
    // --- stored as dedicated database columns ---
    val themeMode: ColorMode = ColorMode.DEFAULT,
    val themeId: String = ThemeIds.OBSIDIAN,
    val accentColor: Long? = null,
    val backgroundColor: Long? = null,
    val fontSize: Int = 100,
    val iconSize: Int = IconLimits.DEFAULT_SIZE,
    val rowHeight: Int = 64,
    val sortingMode: SortMode = SortMode.DEFAULT,
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showWeather: Boolean = false,
    val showFavorites: Boolean = true,
    val showIcons: Boolean = true,
    val showLabels: Boolean = true,
    val showAlphabetRail: Boolean = true,
    val animationEnabled: Boolean = true,
    // --- stored inside the JSON extras column ---
    val extras: LauncherConfigurationExtras = LauncherConfigurationExtras(),
) {
    val typography: TypographyConfig get() = extras.typography
    val wallpaper: WallpaperConfig get() = extras.wallpaper
    val iconConfig: IconConfig get() = extras.iconConfig
    val widgetConfig: WidgetConfig get() = extras.widgetConfig
    val gestureConfig: GestureConfig get() = extras.gestureConfig
    val privacy: PrivacyConfig get() = extras.privacy
    val weather: WeatherConfig get() = extras.weather
    val showGreeting: Boolean get() = extras.showGreeting
    val sectionHeadersEnabled: Boolean get() = extras.sectionHeadersEnabled
    val stickyHeaders: Boolean get() = extras.stickyHeaders
    val clockFormat24h: Boolean? get() = extras.clockFormat24h
    val showSeconds: Boolean get() = extras.showSeconds
    val searchEnabled: Boolean get() = extras.searchEnabled
    val alphabetRailPosition: RailPosition
        get() = if (showAlphabetRail) extras.alphabetRailPosition else RailPosition.HIDDEN

    /** Font scale as a multiplier, already clamped. */
    val fontScale: Float get() = TypographyLimits.clampFontScale(fontSize) / 100f

    /**
     * Normalises everything into a valid configuration. Called on read so that a
     * hand-edited or older backup can never put the UI into an impossible state.
     */
    fun sanitised(): LauncherConfiguration = copy(
        fontSize = TypographyLimits.clampFontScale(fontSize),
        iconSize = IconLimits.clampSize(iconSize),
        rowHeight = ListLimits.clampRowHeight(rowHeight),
        extras = extras.copy(
            typography = extras.typography.copy(
                fontScalePercent = TypographyLimits.clampFontScale(extras.typography.fontScalePercent),
                appLabelSizeSp = TypographyLimits.clampLabelSize(extras.typography.appLabelSizeSp),
                clockSizeSp = TypographyLimits.clampClockSize(extras.typography.clockSizeSp),
                letterSpacingEm = TypographyLimits.clampLetterSpacing(extras.typography.letterSpacingEm),
                lineHeightMultiplier = TypographyLimits.clampLineHeight(extras.typography.lineHeightMultiplier),
            ),
            wallpaper = extras.wallpaper.copy(
                dimAmount = WallpaperLimits.clampDim(extras.wallpaper.dimAmount),
                blurRadiusDp = WallpaperLimits.clampBlur(extras.wallpaper.blurRadiusDp),
            ),
            iconConfig = extras.iconConfig.copy(sizeDp = IconLimits.clampSize(extras.iconConfig.sizeDp)),
            widgetConfig = extras.widgetConfig.copy(spacingDp = WidgetLimits.clampSpacing(extras.widgetConfig.spacingDp)),
            gestureConfig = extras.gestureConfig.copy(
                sensitivity = GestureConfig.clampSensitivity(extras.gestureConfig.sensitivity),
            ),
            privacy = extras.privacy.copy(
                searchHistoryLimit = PrivacyConfig.clampHistoryLimit(extras.privacy.searchHistoryLimit),
            ),
            favoritesMaxItems = FavoriteLimits.clamp(extras.favoritesMaxItems),
            listSpacingDp = ListLimits.clampSpacing(extras.listSpacingDp),
            horizontalPaddingDp = ListLimits.clampHorizontalPadding(extras.horizontalPaddingDp),
            topPaddingDp = ListLimits.clampVerticalPadding(extras.topPaddingDp),
            bottomPaddingDp = ListLimits.clampVerticalPadding(extras.bottomPaddingDp),
            labelSizeSp = TypographyLimits.clampLabelSize(extras.labelSizeSp),
        ),
    )

    /** Applies the global font scale to a size expressed in sp. */
    fun scaled(sp: Float): Float = sp * fontScale

    companion object {
        val DEFAULT = LauncherConfiguration()

        fun defaults(): LauncherConfiguration = DEFAULT
    }
}

/** Everything that does not have a dedicated column in the settings table. */
@Serializable
data class LauncherConfigurationExtras(
    val typography: TypographyConfig = TypographyConfig(),
    val wallpaper: WallpaperConfig = WallpaperConfig(),
    val iconConfig: IconConfig = IconConfig(),
    val widgetConfig: WidgetConfig = WidgetConfig(),
    val gestureConfig: GestureConfig = GestureConfig(),
    val privacy: PrivacyConfig = PrivacyConfig(),
    val weather: WeatherConfig = WeatherConfig(),
    val showGreeting: Boolean = false,
    val sectionHeadersEnabled: Boolean = false,
    val stickyHeaders: Boolean = false,
    /** `null` means "follow the 12/24 hour system setting". */
    val clockFormat24h: Boolean? = null,
    val showSeconds: Boolean = false,
    val searchEnabled: Boolean = true,
    val alphabetRailPosition: RailPosition = RailPosition.END,
    val favoritesLayout: FavoritesLayout = FavoritesLayout.COMPACT,
    val favoritesMaxItems: Int = FavoriteLimits.DEFAULT_MAXIMUM,
    val favoritesShowIcons: Boolean = true,
    val favoritesShowLabels: Boolean = false,
    val showDock: Boolean = false,
    val dockAppKeys: List<AppKey> = emptyList(),
    val listSpacingDp: Int = 2,
    val horizontalPaddingDp: Int = 20,
    val topPaddingDp: Int = 16,
    val bottomPaddingDp: Int = 28,
    val labelSizeSp: Int = 15,
    val fuzzySearch: Boolean = true,
    val hideWorkProfileApps: Boolean = false,
    val showRecentApps: Boolean = false,
    val animationSpeedPercent: Int = 100,
    val hapticsEnabled: Boolean = true,
)

/** Stable identifiers for the built-in themes. */
object ThemeIds {
    const val OBSIDIAN = "obsidian"
    const val PAPER = "paper"
    const val MIDNIGHT_BLUE = "midnight_blue"
    const val FOREST = "forest"
    const val ROSE = "rose"
    const val MONOCHROME = "monochrome"

    const val CUSTOM_PREFIX = "custom:"
}

/** Numeric bounds for list layout settings. */
object ListLimits {
    val ROW_HEIGHT_MIN = 44
    val ROW_HEIGHT_MAX = 120
    val ROW_HEIGHT_DEFAULT = 64
    val SPACING_MIN = 0
    val SPACING_MAX = 24
    val PADDING_MIN = 0
    val PADDING_MAX = 64

    fun clampRowHeight(value: Int): Int = value.coerceIn(ROW_HEIGHT_MIN, ROW_HEIGHT_MAX)
    fun clampSpacing(value: Int): Int = value.coerceIn(SPACING_MIN, SPACING_MAX)
    fun clampHorizontalPadding(value: Int): Int = value.coerceIn(PADDING_MIN, PADDING_MAX)
    fun clampVerticalPadding(value: Int): Int = value.coerceIn(PADDING_MIN, PADDING_MAX)
}

/** Bounds for the animation speed setting. */
object AnimationLimits {
    val SPEED_MIN = 25
    val SPEED_MAX = 200
    val DEFAULT = 100

    fun clampSpeed(value: Int): Int = value.coerceIn(SPEED_MIN, SPEED_MAX)

    /** Converts a percentage into a Compose animation duration. */
    fun duration(baseMillis: Int, speedPercent: Int): Int {
        val factor = DEFAULT.toFloat() / clampSpeed(speedPercent).toFloat()
        return (baseMillis * factor).toInt().coerceIn(0, 2_000)
    }
}

/** Convenience for reading a `Color` out of an ARGB long. */
fun Long.toComposeColor(): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(this)
