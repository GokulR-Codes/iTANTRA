package com.itantra.alert

/**
 * Text normalization for rule matching (master prompt §10).
 *
 * Deliberately minimal: lowercases and collapses whitespace only. Numbers, negation words
 * ("no", "not", "never", ...), location words, and punctuation (including "?") are all
 * preserved unchanged, because [NegationDetector], [ContextAnalyzer], and
 * [CriticalEntityExtractor] all depend on them still being present (§10: "Preserve
 * Numbers... Negation-related words... Important punctuation... Original unmodified text").
 * [EmergencyAnalysis.originalText] always stores the true original, never this normalized
 * copy — normalization output is only ever used internally for matching.
 */
object TextNormalizer {
    private val whitespaceRegex = Regex("\\s+")

    fun normalize(text: String): String =
        text.lowercase().trim().replace(whitespaceRegex, " ")
}
