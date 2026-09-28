package com.minimalflow.launcher.core.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.minimalflow.launcher.core.data.PreferencesRepository
import com.minimalflow.launcher.core.data.setExtras
import com.minimalflow.launcher.core.model.WeatherConfig
import com.minimalflow.launcher.core.model.WeatherLocation
import com.minimalflow.launcher.core.model.WeatherSnapshot
import com.minimalflow.launcher.core.permissions.AppAccess
import com.minimalflow.launcher.core.permissions.PermissionCoordinator
import com.minimalflow.launcher.di.Dispatcher
import com.minimalflow.launcher.di.DispatcherKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What a refresh did, so the settings screen can report it honestly. */
sealed interface WeatherRefresh {
    data class Updated(val snapshot: WeatherSnapshot) : WeatherRefresh

    /** The network call failed; the cached reading is still being shown. */
    data object Unavailable : WeatherRefresh

    /** Nothing to fetch: the feature is off, or there is no location to fetch for. */
    data object NotConfigured : WeatherRefresh
}

/**
 * Owns the weather feature: the cached reading, when to refresh it, and where the
 * coordinates come from.
 *
 * The module is off by default and the launcher is fully functional without it.
 * A cached reading is always kept and always shown with its age, because a stale
 * temperature presented as a live one is worse than no weather at all.
 *
 * Device location is only ever consulted when the user turned it on *and* granted
 * the coarse permission. A manually chosen city needs no permission whatsoever,
 * which is why manual selection is the first thing the settings offer.
 */
@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: WeatherService,
    private val preferences: PreferencesRepository,
    private val permissions: PermissionCoordinator,
    @Dispatcher(DispatcherKind.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /** The last reading, or `null` when there has never been one. */
    val cached: Flow<WeatherSnapshot?> = preferences.weatherCache

    val config: Flow<WeatherConfig> = preferences.configuration.map { it.weather }

    /** City lookup. Suspending because it is a network call. */
    suspend fun searchCities(query: String): List<WeatherLocation> = withContext(ioDispatcher) {
        service.searchCities(query)
    }

    suspend fun selectCity(location: WeatherLocation) {
        preferences.setExtras { extras ->
            extras.copy(
                weather = extras.weather.copy(
                    enabled = true,
                    cityName = location.name,
                    latitude = location.latitude,
                    longitude = location.longitude,
                ),
            )
        }
        refresh()
    }

    suspend fun setEnabled(enabled: Boolean) {
        preferences.setExtras { extras ->
            extras.copy(weather = extras.weather.copy(enabled = enabled))
        }
    }

    suspend fun setUseDeviceLocation(useDeviceLocation: Boolean) {
        preferences.setExtras { extras ->
            extras.copy(weather = extras.weather.copy(useDeviceLocation = useDeviceLocation))
        }
    }

    suspend fun clearCache() {
        preferences.setWeatherCache(null)
    }

    /**
     * Refreshes the cached reading when the feature is on and a location is known.
     *
     * Returns what happened instead of throwing, because a weather failure is never
     * worth interrupting a home screen for.
     */
    suspend fun refresh(): WeatherRefresh = withContext(ioDispatcher) {
        val config = preferences.currentConfiguration().weather
        if (!config.enabled) return@withContext WeatherRefresh.NotConfigured

        val latitude: Double
        val longitude: Double
        var cityName = config.cityName

        when {
            config.hasLocation -> {
                latitude = config.latitude!!
                longitude = config.longitude!!
            }

            config.useDeviceLocation -> {
                val fix = deviceCoordinates() ?: return@withContext WeatherRefresh.NotConfigured
                latitude = fix.first
                longitude = fix.second
                cityName = reverseGeocode(latitude, longitude) ?: cityName
            }

            else -> return@withContext WeatherRefresh.NotConfigured
        }

        val snapshot = service.fetchWeather(latitude, longitude, cityName)
        if (snapshot == null) {
            Log.i(TAG, "Weather refresh failed; keeping the cached reading.")
            return@withContext WeatherRefresh.Unavailable
        }
        preferences.setWeatherCache(snapshot)
        WeatherRefresh.Updated(snapshot)
    }

    /**
     * The last known coarse fix, if the user granted the permission.
     *
     * `LocationManager` rather than Play Services: a launcher must work on devices
     * without Google services, and a city-level answer is all a weather line needs.
     * Only the *last known* fix is read, so nothing here starts a GPS session or
     * keeps the radio awake.
     */
    // Lint cannot see that the guard below runs first, and the read is already
    // wrapped so that a revocation racing this call degrades to "no location"
    // rather than a crash.
    @SuppressLint("MissingPermission")
    private fun deviceCoordinates(): Pair<Double, Double>? {
        if (!permissions.isGranted(AppAccess.COARSE_LOCATION)) return null
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        val best = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull(Location::getTime)

        return best?.let { it.latitude to it.longitude }
    }

    /** Turns a coordinate into a city name for display. */
    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
            results?.firstOrNull()?.locality
                ?: results?.firstOrNull()?.subAdminArea
                ?: results?.firstOrNull()?.adminArea
        }.getOrElse { error ->
            Log.i(TAG, "Could not resolve $latitude,$longitude to a city name: ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "Weather"
    }
}
