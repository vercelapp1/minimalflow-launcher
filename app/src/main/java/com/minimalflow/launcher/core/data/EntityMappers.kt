package com.minimalflow.launcher.core.data

import com.minimalflow.launcher.core.model.AppAlias
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.ColorMode
import com.minimalflow.launcher.core.model.FavoriteApp
import com.minimalflow.launcher.core.model.HiddenApp
import com.minimalflow.launcher.core.model.IconLimits
import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.LauncherConfigurationExtras
import com.minimalflow.launcher.core.model.ListLimits
import com.minimalflow.launcher.core.model.SortMode
import com.minimalflow.launcher.core.model.ThemeColors
import com.minimalflow.launcher.core.model.ThemeConfig
import com.minimalflow.launcher.core.model.ThemeIds
import com.minimalflow.launcher.core.model.WidgetPlacement
import kotlinx.serialization.json.Json

/**
 * Entity <-> model translation.
 *
 * All of it is pure so the round trip can be unit tested without Android. Enum
 * values are stored as their `name`, and reading back always falls back to a
 * default instead of throwing, so a database written by a newer build (or hand
 * edited on a rooted device) can never crash the launcher.
 */
object EntityMappers {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    // ---------------------------------------------------------------- settings

    fun LauncherSettingsEntity.toConfiguration(): LauncherConfiguration {
        val extras = runCatching {
            json.decodeFromString(
                LauncherConfigurationExtras.serializer(),
                extendedJson,
            )
        }.getOrElse { LauncherConfigurationExtras() }

        return LauncherConfiguration(
            themeMode = themeMode.toEnum(ColorMode.DEFAULT),
            themeId = themeId.ifBlank { ThemeIds.OBSIDIAN },
            accentColor = accentColor,
            backgroundColor = backgroundColor,
            fontSize = fontSize,
            iconSize = iconSize,
            rowHeight = rowHeight,
            sortingMode = sortingMode.toEnum(SortMode.DEFAULT),
            showClock = showClock,
            showDate = showDate,
            showWeather = showWeather,
            showFavorites = showFavorites,
            showIcons = showIcons,
            showLabels = showLabels,
            showAlphabetRail = showAlphabetRail,
            animationEnabled = animationEnabled,
            extras = extras,
        ).sanitised()
    }

    fun LauncherConfiguration.toEntity(): LauncherSettingsEntity = sanitised().let { config ->
        LauncherSettingsEntity(
            id = LauncherSettingsEntity.SINGLETON_ID,
            themeMode = config.themeMode.name,
            themeId = config.themeId,
            accentColor = config.accentColor,
            backgroundColor = config.backgroundColor,
            fontSize = config.fontSize,
            iconSize = config.iconSize,
            rowHeight = config.rowHeight,
            sortingMode = config.sortingMode.name,
            showClock = config.showClock,
            showDate = config.showDate,
            showWeather = config.showWeather,
            showFavorites = config.showFavorites,
            showIcons = config.showIcons,
            showLabels = config.showLabels,
            showAlphabetRail = config.showAlphabetRail,
            animationEnabled = config.animationEnabled,
            extendedJson = json.encodeToString(
                LauncherConfigurationExtras.serializer(),
                config.extras,
            ),
        )
    }

    // --------------------------------------------------------------- favourites

    fun FavoriteAppEntity.toModel(): FavoriteApp = FavoriteApp(
        key = AppKey(packageName, componentName, userSerial),
        displayOrder = displayOrder,
        addedAt = addedAt,
    )

    fun FavoriteApp.toEntity(): FavoriteAppEntity = FavoriteAppEntity(
        packageName = key.packageName,
        componentName = key.activityClassName,
        userSerial = key.userSerial,
        displayOrder = displayOrder,
        addedAt = addedAt,
    )

    // ------------------------------------------------------------------ hidden

    fun HiddenAppEntity.toModel(): HiddenApp = HiddenApp(
        key = AppKey(packageName, componentName, userSerial),
        hiddenAt = hiddenAt,
    )

    fun HiddenApp.toEntity(): HiddenAppEntity = HiddenAppEntity(
        packageName = key.packageName,
        componentName = key.activityClassName,
        userSerial = key.userSerial,
        hiddenAt = hiddenAt,
    )

    // ------------------------------------------------------------------ aliases

    fun AppAliasEntity.toModel(): AppAlias = AppAlias(
        key = AppKey(packageName, componentName, userSerial),
        customLabel = customLabel,
        searchKeywords = searchKeywords.split(KEYWORD_SEPARATOR).filter { it.isNotBlank() },
        updatedAt = updatedAt,
    )

    fun AppAlias.toEntity(): AppAliasEntity = AppAliasEntity(
        packageName = key.packageName,
        componentName = key.activityClassName,
        userSerial = key.userSerial,
        customLabel = cleanedLabel(),
        searchKeywords = normalisedKeywords().joinToString(KEYWORD_SEPARATOR),
        updatedAt = updatedAt,
    )

    // ------------------------------------------------------------------ themes

    fun CustomThemeEntity.toModel(): ThemeConfig = ThemeConfig(
        id = id,
        name = name,
        colors = ThemeColors(
            background = backgroundColor,
            surface = surfaceColor,
            primaryText = primaryTextColor,
            secondaryText = secondaryTextColor,
            accent = accentColor,
            divider = dividerColor,
            error = errorColor,
        ),
        isDark = isDark,
        isBuiltIn = false,
        cornerRadiusDp = cornerRadiusDp,
    )

    fun ThemeConfig.toEntity(createdAt: Long): CustomThemeEntity = CustomThemeEntity(
        id = id,
        name = name,
        backgroundColor = colors.background,
        surfaceColor = colors.surface,
        primaryTextColor = colors.primaryText,
        secondaryTextColor = colors.secondaryText,
        accentColor = colors.accent,
        dividerColor = colors.divider,
        errorColor = colors.error,
        fontFamily = FONT_FAMILY_SYSTEM,
        fontSize = 100,
        iconSize = IconLimits.DEFAULT_SIZE,
        rowHeight = ListLimits.ROW_HEIGHT_DEFAULT,
        isDark = isDark,
        cornerRadiusDp = cornerRadiusDp,
        createdAt = createdAt,
    )

    // ----------------------------------------------------------------- widgets

    fun WidgetPlacementEntity.toModel(): WidgetPlacement = WidgetPlacement(
        appWidgetId = appWidgetId,
        providerPackage = providerPackage,
        providerClass = providerClass,
        position = position,
        widthSp = widthSp,
        heightDp = heightDp,
        createdAt = createdAt,
        isCustomised = isCustomised,
    )

    fun WidgetPlacement.toEntity(): WidgetPlacementEntity = WidgetPlacementEntity(
        appWidgetId = appWidgetId,
        providerPackage = providerPackage,
        providerClass = providerClass,
        position = position,
        widthSp = widthSp,
        heightDp = heightDp,
        createdAt = createdAt,
        isCustomised = isCustomised,
    )

    // ----------------------------------------------------------------- history

    fun SearchHistoryEntity.toModel(): Pair<String, Long> = term to lastUsedAt

    // ------------------------------------------------------------------ helper

    private inline fun <reified T : Enum<T>> String.toEnum(fallback: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: fallback

    const val KEYWORD_SEPARATOR = "\u001F"
    const val FONT_FAMILY_SYSTEM = "system"
}
