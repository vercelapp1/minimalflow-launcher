package com.minimalflow.launcher.core.model

import kotlinx.serialization.Serializable

/**
 * The last weather reading the launcher received, together with the time it was
 * fetched.
 *
 * Caching it locally is what lets the home screen show something useful with no
 * network at all, and the timestamp is always shown next to the reading so a
 * stale value is never mistaken for a live one.
 */
@Serializable
data class WeatherSnapshot(
    val cityName: String,
    val temperatureCelsius: Double,
    val temperatureMinCelsius: Double? = null,
    val temperatureMaxCelsius: Double? = null,
    val weatherCode: WeatherCode = WeatherCode.UNKNOWN,
    val observedAtEpochMillis: Long = 0L,
) {
    val isStale: Boolean
        get() = System.currentTimeMillis() - observedAtEpochMillis > STALE_AFTER_MILLIS

    fun temperature(unit: TemperatureUnit): Double = when (unit) {
        TemperatureUnit.CELSIUS -> temperatureCelsius
        TemperatureUnit.FAHRENHEIT -> temperatureCelsius * 9f / 5f + 32f
    }

    fun temperatureMin(unit: TemperatureUnit): Double? = temperatureMinCelsius?.let {
        when (unit) {
            TemperatureUnit.CELSIUS -> it
            TemperatureUnit.FAHRENHEIT -> it * 9f / 5f + 32f
        }
    }

    fun temperatureMax(unit: TemperatureUnit): Double? = temperatureMaxCelsius?.let {
        when (unit) {
            TemperatureUnit.CELSIUS -> it
            TemperatureUnit.FAHRENHEIT -> it * 9f / 5f + 32f
        }
    }

    companion object {
        const val STALE_AFTER_MILLIS = 3 * 60 * 60 * 1000L
    }
}

/**
 * WMO weather interpretation codes, the subset Open-Meteo returns.
 *
 * The launcher renders a text label and an emoji-free glyph chosen from a small
 * built-in set rather than shipping bitmap artwork.
 */
@Serializable
enum class WeatherCode(val label: String, val glyph: WeatherGlyph) {
    CLEAR("Clear", WeatherGlyph.SUN),
    PARTLY_CLOUDY("Partly cloudy", WeatherGlyph.CLOUD_SUN),
    CLOUDY("Cloudy", WeatherGlyph.CLOUD),
    OVERCAST("Overcast", WeatherGlyph.CLOUD),
    FOG("Fog", WeatherGlyph.FOG),
    DRIZZLE("Drizzle", WeatherGlyph.DRIZZLE),
    FREEZING_DRIZZLE("Freezing drizzle", WeatherGlyph.SLEET),
    RAIN("Rain", WeatherGlyph.RAIN),
    FREEZING_RAIN("Freezing rain", WeatherGlyph.SLEET),
    SNOW("Snow", WeatherGlyph.SNOW),
    SHOWERS("Showers", WeatherGlyph.RAIN),
    SNOW_SHOWERS("Snow showers", WeatherGlyph.SLEET),
    THUNDERSTORM("Thunderstorm", WeatherGlyph.STORM),
    THUNDERSTORM_HAIL("Thunderstorm with hail", WeatherGlyph.STORM),
    UNKNOWN("Unknown", WeatherGlyph.CLOUD);

    companion object {
        /**
         * Maps a WMO weather interpretation code onto a condition.
         *
         * The freezing and hail variants have their own entries rather than being
         * folded into the generic ones: "freezing rain" and "rain" are different
         * enough to a person choosing whether to take a coat, and lumping them
         * together would also leave those enum entries unreachable.
         */
        fun from(wmoCode: Int): WeatherCode = when (wmoCode) {
            0 -> CLEAR
            1 -> PARTLY_CLOUDY
            2 -> CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57 -> FREEZING_DRIZZLE
            61, 63, 65 -> RAIN
            66, 67 -> FREEZING_RAIN
            71, 73, 75, 77 -> SNOW
            80, 81, 82 -> SHOWERS
            85, 86 -> SNOW_SHOWERS
            95 -> THUNDERSTORM
            96, 99 -> THUNDERSTORM_HAIL
            else -> UNKNOWN
        }
    }
}

/** Small set of vector glyphs the home screen draws for the weather code. */
enum class WeatherGlyph {
    SUN,
    CLOUD_SUN,
    CLOUD,
    FOG,
    DRIZZLE,
    RAIN,
    SLEET,
    SNOW,
    STORM,
}

/** A city returned by the geocoding endpoint. */
data class WeatherLocation(
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
) {
    val displayName: String get() = if (country.isBlank()) name else "$name, $country"
}
