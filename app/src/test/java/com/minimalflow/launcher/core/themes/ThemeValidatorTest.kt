package com.minimalflow.launcher.core.themes

import com.minimalflow.launcher.core.model.ThemeColors
import com.minimalflow.launcher.core.model.ThemeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theme validation and contrast.
 *
 * A theme that fails its own contrast check is the one thing a launcher can get
 * genuinely wrong rather than merely ugly: unreadable text on a home screen is
 * worse than no theme at all, so the maths is tested rather than eyeballed.
 */
class ThemeValidatorTest {

    @Test
    fun `black and white are the extremes of the contrast ratio`() {
        assertEquals(21.0, ThemeValidator.contrast(0xFF000000L, 0xFFFFFFFFL), 0.05)
    }

    @Test
    fun `a colour has no useful contrast with itself`() {
        assertTrue(ThemeValidator.contrast(0xFF808080L, 0xFF808080L) < 2.0)
    }

    @Test
    fun `contrast is symmetric`() {
        val forward = ThemeValidator.contrast(0xFF1A1A1AL, 0xFFF0F0F0L)
        val backward = ThemeValidator.contrast(0xFFF0F0F0L, 0xFF1A1A1AL)
        assertEquals(forward, backward, 0.0001)
    }

    @Test
    fun `white on black passes the body text floor`() {
        val result = ThemeValidator.checkContrast(0xFFFFFFFFL, 0xFF000000L)
        assertTrue(result.passesBodyText)
        assertTrue(result.passes)
    }

    @Test
    fun `mid grey on mid grey fails`() {
        assertFalse(ThemeValidator.checkContrast(0xFF808080L, 0xFF808080L).passesBodyText)
    }

    @Test
    fun `every built-in theme passes validation`() {
        BuiltInThemes.all.forEach { theme ->
            val result = ThemeValidator.validate(theme)
            assertTrue("${theme.name}: ${result.describe()}", result.isValid)
        }
    }

    @Test
    fun `a theme with no name is rejected`() {
        val theme = ThemeConfig(id = "test", name = "   ")
        assertFalse(ThemeValidator.validate(theme).isValid)
    }

    @Test
    fun `a fully transparent colour is rejected`() {
        val theme = ThemeConfig(
            id = "test",
            name = "Test",
            colors = ThemeColors(background = 0x00000000L),
        )
        assertFalse(ThemeValidator.validate(theme).isValid)
    }

    @Test
    fun `auto-corrected colours are readable`() {
        val corrected = ThemeValidator.autoCorrect(
            colors = ThemeColors(
                background = 0xFFFFFFFFL,
                surface = 0xFFFFFFFFL,
                primaryText = 0xFFFFFFFFL,
            ),
            isDark = false,
        )
        assertTrue(
            ThemeValidator.checkContrast(corrected.primaryText, corrected.background).passesBodyText,
        )
    }
}
