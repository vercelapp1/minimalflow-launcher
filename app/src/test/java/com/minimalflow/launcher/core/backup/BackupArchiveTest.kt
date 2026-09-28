package com.minimalflow.launcher.core.backup

import com.minimalflow.launcher.core.model.LauncherConfiguration
import com.minimalflow.launcher.core.model.SortMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup format compatibility.
 *
 * The point of these tests is forward and backward tolerance: a file written by an
 * older build has to stay readable, and a file from a *newer* build has to be
 * refused cleanly rather than half-applied.
 */
class BackupArchiveTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    private fun archive(
        version: Int = BackupArchive.CURRENT_FORMAT_VERSION,
        configuration: LauncherConfiguration = LauncherConfiguration(),
    ) = BackupArchive(
        formatVersion = version,
        createdAtEpochMillis = 1_700_000_000_000L,
        appVersionName = "1.0",
        configuration = configuration,
    )

    @Test
    fun `a round trip preserves the archive exactly`() {
        val original = archive(
            configuration = LauncherConfiguration(
                sortingMode = SortMode.FREQUENT,
                rowHeight = 80,
                extras = LauncherConfigurationExtrasWithWeather(),
            ),
        )
        val text = json.encodeToString(BackupArchive.serializer(), original)
        val decoded = json.decodeFromString(BackupArchive.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `an unknown field from a future build is ignored rather than fatal`() {
        val text = """
            {
              "formatVersion": 1,
              "createdAtEpochMillis": 1700000000000,
              "configuration": { "fontSize": 100, "somethingNew": 42 }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(BackupArchive.serializer(), text)
        assertEquals(100, decoded.configuration.fontSize)
    }

    @Test
    fun `the current version is readable`() {
        assertTrue(archive().isReadable)
    }

    @Test
    fun `a future version is refused`() {
        assertFalse(archive(version = BackupArchive.CURRENT_FORMAT_VERSION + 1).isReadable)
    }

    @Test
    fun `version zero is refused`() {
        assertFalse(archive(version = 0).isReadable)
    }

    @Test
    fun `optional collections default to empty rather than being required`() {
        // `configuration` is the one required field: without it there is nothing
        // to restore, and silently importing a settings-less backup would wipe the
        // user's configuration instead of replacing it.
        val text = """
            {
              "formatVersion": 1,
              "createdAtEpochMillis": 1,
              "configuration": { "fontSize": 100 }
            }
        """.trimIndent()
        val decoded = json.decodeFromString(BackupArchive.serializer(), text)
        assertTrue(decoded.favorites.isEmpty())
        assertTrue(decoded.customThemes.isEmpty())
        assertTrue(decoded.usage.isEmpty())
        assertTrue(decoded.searchHistory.isEmpty())
        assertEquals(100, decoded.configuration.fontSize)
    }

    @Test
    fun `an archive without a configuration is refused rather than half-applied`() {
        val text = """
            { "formatVersion": 1, "createdAtEpochMillis": 1 }
        """.trimIndent()
        val thrown = runCatching { json.decodeFromString(BackupArchive.serializer(), text) }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `widget placements are deliberately not part of the archive`() {
        // A widget id belongs to the device that allocated it, so carrying one to
        // another device would restore rows pointing at nothing.
        val text = json.encodeToString(BackupArchive.serializer(), archive())
        assertFalse(text.contains("widgetId"))
    }

    /** A configuration with a non-default nested value, to prove extras survive. */
    private fun LauncherConfigurationExtrasWithWeather() =
        com.minimalflow.launcher.core.model.LauncherConfigurationExtras(
            showGreeting = true,
        )
}
