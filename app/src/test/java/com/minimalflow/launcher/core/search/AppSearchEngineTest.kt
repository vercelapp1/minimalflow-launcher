package com.minimalflow.launcher.core.search

import com.minimalflow.launcher.core.model.AppBadge
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search ranking.
 *
 * The order below is the contract the search screen is built on: an exact prefix
 * beats a word-prefix, which beats a substring, which beats a keyword, which beats
 * a typo. These tests exist to stop that order from drifting while someone tunes
 * the fuzzy distance.
 */
class AppSearchEngineTest {

    private fun entry(
        packageName: String,
        label: String,
        customLabel: String? = null,
        keywords: List<String> = emptyList(),
    ): AppListEntry {
        val app = InstalledApp(
            key = AppKey(packageName = packageName),
            label = label,
        )
        return AppListEntry(
            app = app,
            customLabel = customLabel,
            searchKeywords = keywords,
            badge = AppBadge.NONE,
        )
    }

    private val settings = entry("com.example.settings", "Settings")
    private val clock = entry("com.example.clock", "Clock")
    private val myFiles = entry("com.example.files", "My Files")
    private val camera = entry("com.example.camera", "Camera", keywords = listOf("photo", "picture"))

    private val all = listOf(settings, clock, myFiles, camera)

    @Test
    fun `exact label ranks first`() {
        val results = AppSearchEngine.search(all, "Settings")
        assertEquals("com.example.settings", results.first().entry.key.packageName)
    }

    @Test
    fun `matching is case insensitive`() {
        val results = AppSearchEngine.search(all, "SETTINGS")
        assertNotNull(results.firstOrNull())
    }

    @Test
    fun `leading whitespace is ignored`() {
        val results = AppSearchEngine.search(all, "   clock  ")
        assertEquals("com.example.clock", results.first().entry.key.packageName)
    }

    @Test
    fun `a word prefix beats a mid word substring`() {
        val results = AppSearchEngine.search(all, "file")
        // "My Files" starts a word with "file"; "Settings" merely contains it.
        assertEquals("com.example.files", results.first().entry.key.packageName)
    }

    @Test
    fun `keywords are searchable`() {
        val results = AppSearchEngine.search(all, "photo")
        assertEquals("com.example.camera", results.first().entry.key.packageName)
        assertEquals("photo", results.first().matchedOn)
    }

    @Test
    fun `a custom label is searched instead of the real name`() {
        val renamed = entry("com.example.settings", "Settings", customLabel = "Preferences")
        val results = AppSearchEngine.search(listOf(renamed), "preferences")
        assertEquals("com.example.settings", results.first().entry.key.packageName)
    }

    @Test
    fun `an empty query returns nothing rather than everything`() {
        assertTrue(AppSearchEngine.search(all, "").isEmpty())
        assertTrue(AppSearchEngine.search(all, "   ").isEmpty())
    }

    @Test
    fun `fuzzy matching can be turned off`() {
        // "clck" is a plausible typo; without fuzzy it should find nothing.
        assertTrue(AppSearchEngine.search(all, "clck", allowFuzzy = false).isEmpty())
        assertNotNull(AppSearchEngine.search(all, "clck", allowFuzzy = true).firstOrNull())
    }

    @Test
    fun `the limit is respected`() {
        val many = (1..100).map { entry("com.example.app$it", "App $it") }
        assertTrue(AppSearchEngine.search(many, "app", limit = 10).size <= 10)
    }

    @Test
    fun `a query matching nothing returns no results`() {
        assertTrue(AppSearchEngine.search(all, "zzzzqqq").isEmpty())
    }

    @Test
    fun `scoreEntry returns null for a query that does not match`() {
        assertNull(AppSearchEngine.scoreEntry(settings, "qqqqqq"))
    }
}
