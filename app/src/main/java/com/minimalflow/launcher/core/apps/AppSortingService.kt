package com.minimalflow.launcher.core.apps

import com.minimalflow.launcher.core.model.AppListEntry
import com.minimalflow.launcher.core.model.SortMode
import java.text.BreakIterator
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/**
 * One A to Z section of the app list.
 *
 * [letter] is the display text: either a single upper-cased character or
 * [NUMERIC_SECTION] for apps whose name starts with a digit or a symbol.
 */
data class AppSection(
    val letter: String,
    val entries: List<AppListEntry>,
) {
    val size: Int get() = entries.size
}

/**
 * Locale-aware ordering and grouping.
 *
 * Sorting goes through [Collator] rather than `String.compareTo`, so accented
 * letters land where a speaker of the language expects them, and grouping uses
 * the same collator's *primary* weight. That means "Ärger" files itself under
 * "A" in German and under "Ä" in a locale that treats them as separate letters -
 * both correct, both driven purely by the device locale.
 *
 * The locale is a constructor parameter rather than being read from a static
 * default so tests can assert German and Japanese grouping without mutating
 * global state. In production the injected no-argument constructor always passes
 * [Locale.getDefault]; the application scope is rebuilt when the system language
 * changes, so a stale collator cannot outlive a configuration change.
 */
class AppSortingService(
    val locale: Locale,
) {

    @Inject
    constructor() : this(Locale.getDefault())

    private val collator: Collator = Collator.getInstance(locale).apply {
        // Case is ignored for *ordering*, so "apple" and "Apple" are neighbours
        // and never split across two sections. Accents are still significant,
        // because a speaker of the language expects them to be.
        strength = Collator.SECONDARY
    }

    private val characterBreaks: BreakIterator by lazy { BreakIterator.getCharacterInstance(locale) }

    /**
     * Primary-strength collator, used only to decide which A-Z section a letter
     * belongs to. Primary strength ignores accents and case, which is exactly the
     * question being asked: "is this `Ä` an `A`?".
     */
    private val primaryCollator: Collator = Collator.getInstance(locale).apply {
        strength = Collator.PRIMARY
    }

    /** Section letter per distinct first character; see [sectionLetter]. */
    private val sectionLetterCache = HashMap<String, String>()

    /**
     * Accent- and case-insensitive label order.
     *
     * Written as an explicit lambda rather than `compareBy(collator) { ... }`
     * because `compareBy` has an overload taking a bare selector, and the
     * platform `Collator!` type does not disambiguate the two.
     */
    private val byLabel: Comparator<AppListEntry> =
        Comparator { left, right -> collator.compare(left.displayLabel, right.displayLabel) }

    fun sort(entries: List<AppListEntry>, mode: SortMode): List<AppListEntry> = when (mode) {
        SortMode.ALPHABETICAL -> entries.sortedWith(byLabel)
        SortMode.ALPHABETICAL_REVERSE -> entries.sortedWith(byLabel).reversed()
        SortMode.RECENT -> entries.sortedWith { left, right ->
            val byTime = right.lastLaunchTime.compareTo(left.lastLaunchTime)
            if (byTime != 0) byTime else byLabel.compare(left, right)
        }
        SortMode.FREQUENT -> entries.sortedWith { left, right ->
            val byCount = right.launchCount.compareTo(left.launchCount)
            if (byCount != 0) byCount else byLabel.compare(left, right)
        }
    }

    /**
     * Splits an already sorted list into A to Z sections.
     *
     * The rail advertises exactly the sections that exist, which keeps the
     * alphabet from offering letters that would scroll nowhere.
     */
    fun groupIntoSections(sorted: List<AppListEntry>): List<AppSection> {
        if (sorted.isEmpty()) return emptyList()

        val sections = LinkedHashMap<String, MutableList<AppListEntry>>()
        for (entry in sorted) {
            val letter = sectionLetter(entry.displayLabel)
            sections.getOrPut(letter) { mutableListOf() }.add(entry)
        }
        return sections.map { (letter, entries) -> AppSection(letter, entries) }
    }

    /**
     * The section a label belongs to.
     *
     * Two separate problems are solved here.
     *
     * *Which character starts the label* is a character-segmentation question, so
     * [BreakIterator] answers it: it finds the first real letter, skips combining
     * marks (which belong to the letter they follow) and skips leading whitespace.
     * Anything that starts with a digit or a symbol goes to [NUMERIC_SECTION],
     * which is what keeps "1Password" reachable.
     *
     * *Which section that character belongs to* is a collation question, and this
     * is where a character iterator is not enough. In German, `Ä` is a variant of
     * `A`: a speaker expects "Ärger" filed under A, not under a letter of its own
     * that sits between A and B. So the first letter is mapped onto the plain Latin
     * letter with the same *primary* collation weight, and the cache means that
     * lookup happens once per distinct character rather than once per row.
     */
    fun sectionLetter(label: String): String {
        val first = firstLetterOf(label) ?: return NUMERIC_SECTION
        return sectionLetterCache.getOrPut(first) { canonicalSection(first) }
    }

    /**
     * The plain letter this character collates with, or the character itself when
     * it does not correspond to one - which is the normal answer for CJK and for
     * any script the A-Z rail cannot meaningfully represent.
     */
    private fun canonicalSection(character: String): String {
        for (candidate in LATIN_SECTIONS) {
            if (primaryCollator.compare(character, candidate) == 0) return candidate
        }
        return character
    }

    /**
     * The first real letter in [label], upper-cased, or `null` when the label does
     * not start with one.
     */
    private fun firstLetterOf(label: String): String? {
        characterBreaks.setText(label)
        var index = characterBreaks.first()
        while (index != BreakIterator.DONE) {
            val codePoint = label.codePointAt(index)
            when {
                // Combining marks belong to the letter they follow, so skipping them
                // is what files a decomposed "a" + U+0308 under "A" rather than
                // under a section named after the mark.
                isCombiningMark(codePoint) -> Unit
                Character.isLetter(codePoint) -> {
                    return String(Character.toChars(codePoint)).uppercase(locale)
                }

                Character.isSpaceChar(codePoint) || Character.isWhitespace(codePoint) -> Unit
                else -> return null
            }
            index = characterBreaks.next()
        }
        return null
    }

    private fun isCombiningMark(codePoint: Int): Boolean =
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true

            else -> false
        }

    companion object {
        /** The catch-all section for digits, symbols and everything else. */
        const val NUMERIC_SECTION = "#"

        /** The sections the A-Z rail can offer. */
        private val LATIN_SECTIONS: List<String> =
            ('A'..'Z').map { it.toString() }
    }
}

