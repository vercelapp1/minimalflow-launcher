package com.minimalflow.launcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Configuration sanitising and the limits that back it.
 *
 * `sanitised()` is the last line of defence for everything that can reach a
 * configuration: a hand-edited backup, an older restore, or a value the slider
 * UI clamps. If it ever stops being total, the launcher can end up in a state the
 * settings screen has no way back out of, so it is tested as thoroughly as the
 * writers that feed it.
 */
class LauncherConfigurationTest {

    @Test
    fun `an out of range icon size is clamped, not rejected`() {
        val config = LauncherConfiguration(iconSize = 5_000).sanitised()
        assertEquals(IconLimits.SIZE_MAX, config.iconSize)
    }

    @Test
    fun `a negative icon size is clamped up`() {
        val config = LauncherConfiguration(iconSize = -20).sanitised()
        assertEquals(IconLimits.SIZE_MIN, config.iconSize)
    }

    @Test
    fun `row height is clamped to its limits`() {
        assertEquals(
            ListLimits.ROW_HEIGHT_MAX,
            LauncherConfiguration(rowHeight = 5_000).sanitised().rowHeight,
        )
        assertEquals(
            ListLimits.ROW_HEIGHT_MIN,
            LauncherConfiguration(rowHeight = 1).sanitised().rowHeight,
        )
    }

    @Test
    fun `font size is clamped to its limits`() {
        val tooBig = LauncherConfiguration(fontSize = 1_000).sanitised()
        val tooSmall = LauncherConfiguration(fontSize = 1).sanitised()
        assertTrue(tooBig.fontSize <= TypographyLimits.FONT_SCALE_MAX)
        assertTrue(tooSmall.fontSize >= TypographyLimits.FONT_SCALE_MIN)
    }

    @Test
    fun `search history limit is clamped`() {
        val config = LauncherConfiguration(
            extras = LauncherConfigurationExtras(
                privacy = PrivacyConfig(searchHistoryLimit = 10_000),
            ),
        ).sanitised()
        assertEquals(PrivacyConfig.HISTORY_LIMIT_MAX, config.privacy.searchHistoryLimit)
    }

    @Test
    fun `sanitising is idempotent`() {
        val once = LauncherConfiguration(iconSize = 5_000, rowHeight = 5_000).sanitised()
        assertEquals(once, once.sanitised())
    }

    @Test
    fun `weather needs both coordinates before it can report a location`() {
        assertFalse(WeatherConfig(latitude = 1.0, longitude = null).hasLocation)
        assertFalse(WeatherConfig(latitude = null, longitude = 2.0).hasLocation)
        assertTrue(WeatherConfig(latitude = 1.0, longitude = 2.0).hasLocation)
    }

    @Test
    fun `a disabled widget panel reports itself as disabled`() {
        assertFalse(WidgetConfig(panelPosition = WidgetPanelPosition.DISABLED).isEnabled)
        assertTrue(WidgetConfig(panelPosition = WidgetPanelPosition.ABOVE_LIST).isEnabled)
    }

    @Test
    fun `alphabet rail is hidden when the master switch is off`() {
        val hidden = LauncherConfiguration(
            showAlphabetRail = false,
            extras = LauncherConfigurationExtras(alphabetRailPosition = RailPosition.END),
        )
        assertEquals(RailPosition.HIDDEN, hidden.alphabetRailPosition)
    }

    @Test
    fun `alphabet rail keeps its position when the master switch is on`() {
        val shown = LauncherConfiguration(
            showAlphabetRail = true,
            extras = LauncherConfigurationExtras(alphabetRailPosition = RailPosition.START),
        )
        assertEquals(RailPosition.START, shown.alphabetRailPosition)
    }

    @Test
    fun `font scale is a multiplier between 0_8 and 1_4`() {
        assertEquals(1.0f, LauncherConfiguration(fontSize = 100).fontScale, 0.001f)
        assertEquals(1.4f, LauncherConfiguration(fontSize = 10_000).fontScale, 0.001f)
    }
}
