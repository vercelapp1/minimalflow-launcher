package com.minimalflow.launcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Weather model behaviour.
 *
 * Two things matter here and neither is cosmetic: the WMO code table has to match
 * what the API actually returns, and a cached reading has to report itself as
 * stale once it is old enough that showing it without a timestamp would mislead.
 */
class WeatherSnapshotTest {

    @Test
    fun `WMO codes map to the documented conditions`() {
        assertEquals(WeatherCode.CLEAR, WeatherCode.from(0))
        assertEquals(WeatherCode.PARTLY_CLOUDY, WeatherCode.from(1))
        assertEquals(WeatherCode.CLOUDY, WeatherCode.from(2))
        assertEquals(WeatherCode.OVERCAST, WeatherCode.from(3))
        assertEquals(WeatherCode.FOG, WeatherCode.from(45))
        assertEquals(WeatherCode.DRIZZLE, WeatherCode.from(53))
        assertEquals(WeatherCode.FREEZING_DRIZZLE, WeatherCode.from(56))
        assertEquals(WeatherCode.RAIN, WeatherCode.from(61))
        assertEquals(WeatherCode.FREEZING_RAIN, WeatherCode.from(66))
        assertEquals(WeatherCode.SNOW, WeatherCode.from(75))
        assertEquals(WeatherCode.SHOWERS, WeatherCode.from(80))
        assertEquals(WeatherCode.SNOW_SHOWERS, WeatherCode.from(85))
        assertEquals(WeatherCode.THUNDERSTORM, WeatherCode.from(95))
        assertEquals(WeatherCode.THUNDERSTORM_HAIL, WeatherCode.from(96))
        assertEquals(WeatherCode.THUNDERSTORM_HAIL, WeatherCode.from(99))
    }

    @Test
    fun `every condition the enum declares is reachable from some WMO code`() {
        // Guards against an entry being added to the enum and never produced.
        val reachable = (0..99).map { WeatherCode.from(it) }.toSet()
        WeatherCode.entries.filter { it != WeatherCode.UNKNOWN }.forEach { code ->
            assertTrue("No WMO code maps to $code", code in reachable)
        }
    }

    @Test
    fun `an unrecognised WMO code becomes UNKNOWN rather than throwing`() {
        assertEquals(WeatherCode.UNKNOWN, WeatherCode.from(-1))
        assertEquals(WeatherCode.UNKNOWN, WeatherCode.from(9999))
    }

    @Test
    fun `conversion to Fahrenheit is applied to the stored Celsius value`() {
        val snapshot = WeatherSnapshot(cityName = "Test", temperatureCelsius = 0.0)
        assertEquals(32.0, snapshot.temperature(TemperatureUnit.FAHRENHEIT), 0.01)
        assertEquals(0.0, snapshot.temperature(TemperatureUnit.CELSIUS), 0.01)
    }

    @Test
    fun `a hundred degrees Celsius is two hundred and twelve point eight Fahrenheit`() {
        val snapshot = WeatherSnapshot(cityName = "Test", temperatureCelsius = 100.0)
        assertEquals(212.0, snapshot.temperature(TemperatureUnit.FAHRENHEIT), 0.01)
    }

    @Test
    fun `a missing daily range stays null rather than becoming zero`() {
        val snapshot = WeatherSnapshot(cityName = "Test", temperatureCelsius = 10.0)
        assertEquals(null, snapshot.temperatureMin(TemperatureUnit.CELSIUS))
        assertEquals(null, snapshot.temperatureMax(TemperatureUnit.CELSIUS))
    }

    @Test
    fun `a fresh reading is not stale`() {
        val fresh = WeatherSnapshot(
            cityName = "Test",
            temperatureCelsius = 10.0,
            observedAtEpochMillis = System.currentTimeMillis(),
        )
        assertTrue(!fresh.isStale)
    }

    @Test
    fun `a reading older than the stale window reports itself as stale`() {
        val old = WeatherSnapshot(
            cityName = "Test",
            temperatureCelsius = 10.0,
            observedAtEpochMillis = System.currentTimeMillis() - WeatherSnapshot.STALE_AFTER_MILLIS - 1,
        )
        assertTrue(old.isStale)
    }

    @Test
    fun `a reading with no timestamp is treated as stale`() {
        val never = WeatherSnapshot(cityName = "Test", temperatureCelsius = 10.0)
        assertTrue(never.isStale)
    }

    @Test
    fun `a city's display name includes the country only when there is one`() {
        assertEquals("Berlin, Germany", WeatherLocation("Berlin", "Germany", 52.5, 13.4).displayName)
        assertEquals("Nowhere", WeatherLocation("Nowhere", "", 0.0, 0.0).displayName)
    }
}
