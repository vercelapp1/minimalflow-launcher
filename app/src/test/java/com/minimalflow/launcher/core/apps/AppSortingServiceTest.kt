package com.minimalflow.launcher.core.apps

import com.minimalflow.launcher.core.model.AppBadge
import com.minimalflow.launcher.core.model.AppKey
import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.InstalledApp
import com.minimalflow.launcher.core.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Sorting and A-Z grouping.
 *
 * The locale is passed in rather than read from a global default so these tests
 * can assert German and Japanese grouping without mutating process state.
 */
class AppSortingServiceTest {

    private fun entry(label: String, launches: Int = 0, lastLaunch: Long = 0L) = AppListEntry(
        app = InstalledApp(key = AppKey(packageName = "com.example.${label.lowercase()}"), label = label),
        launchCount = launches,
        lastLaunchTime = lastLaunch,
        badge = AppBadge.NONE,
    )

    private val english = AppSortingService(Locale.ENGLISH)
    private val german = AppSortingService(Locale.GERMAN)

    private val mixedCase = listOf(entry("banana"), entry("Apple"), entry("cherry"), entry("apricot"))

    @Test
    fun `alphabetical sorting ignores case`() {
        val sorted = english.sort(mixedCase, SortMode.ALPHABETICAL)
        assertEquals(listOf("Apple", "apricot", "banana", "cherry"), sorted.map { it.app.label })
    }

    @Test
    fun `reverse sorting is the exact mirror of forward sorting`() {
        val forward = english.sort(mixedCase, SortMode.ALPHABETICAL).map { it.app.label }
        val reverse = english.sort(mixedCase, SortMode.ALPHABETICAL_REVERSE).map { it.app.label }
        assertEquals(forward.reversed(), reverse)
    }

    @Test
    fun `recent sorting puts the newest first`() {
        val sorted = english.sort(
            listOf(entry("a", lastLaunch = 100L), entry("b", lastLaunch = 300L), entry("c", lastLaunch = 200L)),
            SortMode.RECENT,
        )
        assertEquals(listOf("b", "c", "a"), sorted.map { it.app.label })
    }

    @Test
    fun `frequent sorting puts the most launched first`() {
        val sorted = english.sort(
            listOf(entry("a", launches = 1), entry("b", launches = 9), entry("c", launches = 5)),
            SortMode.FREQUENT,
        )
        assertEquals(listOf("b", "c", "a"), sorted.map { it.app.label })
    }

    @Test
    fun `recent ties fall back to alphabetical order`() {
        val sorted = english.sort(
            listOf(entry("zebra", lastLaunch = 5L), entry("aardvark", lastLaunch = 5L)),
            SortMode.RECENT,
        )
        assertEquals(listOf("aardvark", "zebra"), sorted.map { it.app.label })
    }

    @Test
    fun `grouping produces one section per letter in order`() {
        val sections = english.groupIntoSections(english.sort(mixedCase, SortMode.ALPHABETICAL))
        assertEquals(listOf("A", "B", "C"), sections.map { it.letter })
    }

    @Test
    fun `grouping keeps every entry`() {
        val sections = english.groupIntoSections(english.sort(mixedCase, SortMode.ALPHABETICAL))
        assertEquals(mixedCase.size, sections.sumOf { it.size })
    }

    @Test
    fun `apps starting with a digit or symbol share one numeric section`() {
        val entries = listOf(entry("1Password"), entry("7-Zip"), entry("Adobe"), entry("alpha"))
        val sections = english.groupIntoSections(english.sort(entries, SortMode.ALPHABETICAL))
        val letters = sections.map { it.letter }
        // Digits and symbols collect under a single "#" heading.
        assertTrue(AppSortingService.NUMERIC_SECTION in letters)
        assertEquals(2, sections.first { it.letter == AppSortingService.NUMERIC_SECTION }.size)
        // Every entry is still accounted for.
        assertEquals(entries.size, sections.sumOf { it.size })
    }

    @Test
    fun `german grouping files an umlaut under its base letter`() {
        val entries = listOf(entry("Ärger"), entry("Apfel"), entry("Zebra"))
        val sections = german.groupIntoSections(german.sort(entries, SortMode.ALPHABETICAL))
        val anger = sections.firstOrNull { it.entries.any { it.app.label == "Ärger" } }
        assertEquals("A", anger?.letter)
    }

    @Test
    fun `an empty list produces no sections`() {
        assertTrue(english.groupIntoSections(emptyList()).isEmpty())
    }

    @Test
    fun `section letters are upper case`() {
        val sections = english.groupIntoSections(english.sort(mixedCase, SortMode.ALPHABETICAL))
        sections.forEach { assertEquals(it.letter.uppercase(), it.letter) }
    }
}
