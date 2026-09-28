package com.minimalflow.launcher.core.search

import com.minimalflow.launcher.core.model.AppListEntry

/** Why a result matched, so the UI can explain itself and so scoring is testable. */
enum class MatchKind {
    EXACT,
    PREFIX_LABEL,
    PREFIX_WORD,
    SUBSTRING_LABEL,
    SUBSTRING_KEYWORD,
    SUBSTRING_ALIAS,
    FUZZY,
}

/** A single scored result. Lower [score] sorts first. */
data class SearchResult(
    val entry: AppListEntry,
    val score: Int,
    val kind: MatchKind,
    val matchedOn: String? = null,
) {
    val key get() = entry.key
}

/**
 * In-memory fuzzy matcher over the app list.
 *
 * The whole index is a few hundred short strings, so a linear scan with a cheap
 * subsequence check is faster in practice than anything cleverer and, more
 * importantly, it is easy to reason about. Nothing here touches disk, which is
 * why typing stays smooth with a full app list.
 *
 * Scores are deliberately spread out so a prefix hit always outranks a
 * mid-word hit, and an exact label always outranks everything.
 */
object AppSearchEngine {

    /**
     * Highest - that is, worst - score that is still worth showing.
     *
     * Scores are "lower is better", so this is a ceiling. Anything scored above it
     * matched only by accident (a fuzzy hit in a long, unrelated label) and is
     * noise rather than a result.
     */
    const val MAX_VISIBLE_SCORE = 160

    private const val EXACT = 0
    private const val PREFIX_LABEL = 10
    private const val PREFIX_WORD = 20
    private const val SUBSTRING_LABEL = 30
    private const val SUBSTRING_KEYWORD = 45
    private const val SUBSTRING_ALIAS = 50
    private const val FUZZY = 70
    private const val PREFIX_BONUS_PER_EXTRA_CHAR = 6

    /** Caps the length penalty so a long label cannot out-score a better match. */
    private const val MAX_PENALISED_EXTRA_CHARS = 8

    fun search(
        entries: List<AppListEntry>,
        rawQuery: String,
        limit: Int = 60,
        allowFuzzy: Boolean = true,
    ): List<SearchResult> {
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) return emptyList()

        val results = ArrayList<SearchResult>(entries.size)
        for (entry in entries) {
            val match = scoreEntry(entry, query, allowFuzzy) ?: continue
            if (match.score <= MAX_VISIBLE_SCORE) results += match
        }
        results.sortWith(
            compareBy<SearchResult> { it.score }
                .thenBy { it.entry.displayLabel.length }
                .thenBy { it.entry.displayLabel },
        )
        return if (results.size > limit) results.subList(0, limit).toList() else results
    }

    /** Scores one app, or returns `null` when the query does not match it at all. */
    fun scoreEntry(
        entry: AppListEntry,
        rawQuery: String,
        allowFuzzy: Boolean = true,
    ): SearchResult? {
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) return null

        val label = entry.displayLabelLower
        val originalLabel = entry.displayLabel

        if (label == query) return SearchResult(entry, EXACT, MatchKind.EXACT, originalLabel)

        if (label.startsWith(query)) {
            return SearchResult(
                entry,
                PREFIX_LABEL + lengthPenalty(label.length - query.length),
                MatchKind.PREFIX_LABEL,
                originalLabel,
            )
        }

        // "gm" should find "Gmail": check the start of any word inside the label.
        val wordPrefix = label.indexOfFirstWordStartingWith(query)
        if (wordPrefix > 0) {
            return SearchResult(
                entry,
                PREFIX_WORD + wordPrefix,
                MatchKind.PREFIX_WORD,
                originalLabel,
            )
        }

        val substringAt = label.indexOf(query)
        if (substringAt > 0) {
            return SearchResult(
                entry,
                SUBSTRING_LABEL + substringAt,
                MatchKind.SUBSTRING_LABEL,
                originalLabel,
            )
        }

        // User-defined keywords are searched after the label so a renamed app never
        // outranks a real name match.
        val keyword = entry.searchKeywords
            .firstOrNull { it.isNotEmpty() && it.contains(query) }
        if (keyword != null) {
            return SearchResult(
                entry,
                SUBSTRING_KEYWORD + keyword.indexOf(query),
                MatchKind.SUBSTRING_KEYWORD,
                keyword,
            )
        }

        // A custom label is already part of `displayLabel`; this branch exists for
        // the case where the alias is *not* the displayed name but still matches,
        // which keeps older backups searchable after an alias was cleared.
        val aliasHit = entry.customLabel
            ?.lowercase()
            ?.takeIf { hit ->
                hit.isNotEmpty() && hit.contains(query) && hit != label
            }
        if (aliasHit != null) {
            return SearchResult(
                entry,
                SUBSTRING_ALIAS + aliasHit.indexOf(query),
                MatchKind.SUBSTRING_ALIAS,
                aliasHit,
            )
        }

        if (allowFuzzy) {
            val distance = subsequenceDistance(query, label)
            if (distance != null) {
                return SearchResult(
                    entry,
                    FUZZY + distance + lengthPenalty(label.length - query.length),
                    MatchKind.FUZZY,
                    originalLabel,
                )
            }
        }

        return null
    }

    private fun lengthPenalty(extraCharacters: Int): Int =
        PREFIX_BONUS_PER_EXTRA_CHAR * extraCharacters.coerceIn(0, MAX_PENALISED_EXTRA_CHARS)

    /**
     * Cheap "typo" matching: every query character has to appear in the label in
     * order, with a small allowance for skipped characters. Returning the number
     * of skipped characters makes better candidates sort first.
     */
    private fun subsequenceDistance(query: String, target: String): Int? {
        if (query.isEmpty() || target.isEmpty()) return null
        var targetIndex = 0
        var skipped = 0
        for (ch in query) {
            val found = target.indexOf(ch, targetIndex)
            if (found < 0) return null
            skipped += found - targetIndex
            targetIndex = found + 1
        }
        // Require the match to stay reasonably tight, otherwise "abc" matches most
        // labels and the results become noise.
        val allowance = (target.length / 2).coerceAtLeast(2)
        return if (skipped <= allowance) skipped else null
    }

    private fun String.indexOfFirstWordStartingWith(needle: String): Int {
        if (needle.isEmpty()) return -1
        var index = 0
        while (index < length) {
            val found = indexOf(needle, index)
            if (found < 0) return -1
            val preceding = if (found == 0) ' ' else this[found - 1]
            if (!preceding.isLetterOrDigit()) return found
            index = found + 1
        }
        return -1
    }
}
