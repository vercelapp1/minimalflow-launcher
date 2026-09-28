package com.minimalflow.launcher.core.weather

import android.util.Log
import com.minimalflow.launcher.core.model.WeatherCode
import com.minimalflow.launcher.core.model.WeatherLocation
import com.minimalflow.launcher.core.model.WeatherSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Talks to Open-Meteo.
 *
 * Open-Meteo needs no API key and no account, which is the only reason a launcher
 * can offer weather at all without asking the user to sign up for anything. It
 * also serves plain JSON over HTTPS, so this uses [HttpURLConnection] rather than
 * pulling a networking library into an app that otherwise never touches the
 * network.
 *
 * Everything is parsed with `kotlinx.serialization`'s tree API instead of data
 * classes with `@SerialName`, because the API adds fields regularly and an
 * unknown field must never be able to fail a parse.
 */
@Singleton
class WeatherService @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Looks up cities by name.
     *
     * Returns an empty list on any failure: a failed search should show "no
     * results", never an error state, because the user can simply retype.
     */
    fun searchCities(query: String): List<WeatherLocation> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val url = buildString {
            append(GEOCODING_URL)
            append("?name=").append(URLEncoder.encode(trimmed))
            append("&count=").append(RESULT_LIMIT)
            append("&language=en")
            append("&format=json")
        }

        val root = fetchJsonObject(url) ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()

        return results.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.string("name") ?: return@mapNotNull null
            val latitude = entry.double("latitude") ?: return@mapNotNull null
            val longitude = entry.double("longitude") ?: return@mapNotNull null
            WeatherLocation(
                name = name,
                country = entry.string("country").orEmpty(),
                latitude = latitude,
                longitude = longitude,
            )
        }
    }

    /**
     * Fetches the current conditions and today's range for a coordinate.
     *
     * `null` means the request failed, which the repository turns into "keep
     * showing the cached reading, marked stale" rather than clearing the widget.
     */
    fun fetchWeather(latitude: Double, longitude: Double, cityName: String): WeatherSnapshot? {
        val url = buildString {
            append(FORECAST_URL)
            append("?latitude=").append(latitude)
            append("&longitude=").append(longitude)
            append("&current=").append("temperature_2m,weather_code")
            append("&daily=").append("temperature_2m_max,temperature_2m_min")
            append("&timezone=auto")
            append("&forecast_days=").append(1)
        }

        val root = fetchJsonObject(url) ?: return null
        val current = root["current"] as? JsonObject ?: return null
        val temperature = current.double("temperature_2m") ?: return null
        val code = WeatherCode.from(current.long("weather_code")?.toInt() ?: UNKNOWN_WMO_CODE)

        val daily = root["daily"] as? JsonObject

        return WeatherSnapshot(
            cityName = cityName.ifBlank { DEFAULT_CITY_LABEL },
            temperatureCelsius = temperature,
            temperatureMinCelsius = daily?.doubleAt(0, "temperature_2m_min"),
            temperatureMaxCelsius = daily?.doubleAt(0, "temperature_2m_max"),
            weatherCode = code,
            observedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ transport

    private fun fetchJsonObject(url: String): JsonObject? {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/json")
                // Open-Meteo is a public endpoint; being explicit avoids gzip work
                // for the few hundred bytes a response is.
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                if (connection.responseCode !in HTTP_OK_RANGE) {
                    Log.w(TAG, "Open-Meteo returned ${connection.responseCode} for $url")
                    return null
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                return json.parseToJsonElement(body) as? JsonObject
            } finally {
                connection.disconnect()
            }
        } catch (error: IOException) {
            Log.w(TAG, "Could not reach Open-Meteo", error)
            return null
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Open-Meteo returned something that is not JSON", error)
            return null
        }
    }

    // -------------------------------------------------------------------- parsing

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.doubleAt(index: Int, key: String): Double? {
        val array = (this[key] as? JsonArray) ?: return null
        val element = array.getOrNull(index) ?: return null
        return (element as? JsonPrimitive)?.doubleOrNull
    }

    companion object {
        private const val TAG = "Weather"
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val RESULT_LIMIT = 8
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 8_000
        private const val UNKNOWN_WMO_CODE = -1
        private const val DEFAULT_CITY_LABEL = "Current location"

        private val HTTP_OK_RANGE = 200..299
    }
}
