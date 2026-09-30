package com.itantra.alert

/** Result of a negation scope check around one matched phrase. */
data class NegationFinding(
    val negated: Boolean,
    val cue: String? = null
)

/**
 * Rule-based negation scope detection (master prompt §11).
 *
 * This is explicitly a heuristic word-window check, not real natural-language
 * understanding (§11: "it must not pretend to provide perfect natural-language
 * understanding"). It looks up to [WINDOW_WORDS] tokens *before* a matched phrase, within
 * the same sentence only (it never crosses a `.`/`!`/`?` boundary), for a closed set of
 * negation cue words. Known limitations, deliberately not hidden:
 *  - Does not handle negation appearing *after* the matched phrase ("fire, thankfully not
 *    a serious one" is not recognized as negating "fire").
 *  - Does not handle double negation.
 *  - Contraction handling ("isn't", "doesn't", "won't", "can't") is done via an "n't"
 *    suffix check, which is a coarse approximation of real tokenization.
 */
object NegationDetector {
    private const val WINDOW_WORDS = 4

    private val negationCues = setOf(
        "no", "not", "never", "none", "nobody", "nothing", "without", "denies", "denied", "isn't", "wasn't",
        "aren't", "weren't", "doesn't", "don't", "didn't", "won't", "can't", "cannot", "couldn't", "shouldn't"
    )

    /**
     * @param normalizedText text already passed through [TextNormalizer.normalize].
     * @param matchStart start index (in [normalizedText]) of the phrase to check.
     */
    fun findNegation(normalizedText: String, matchStart: Int): NegationFinding {
        val sentenceStart = lastSentenceBoundary(normalizedText, matchStart)
        val preceding = normalizedText.substring(sentenceStart, matchStart)
        val tokens = preceding.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val window = tokens.takeLast(WINDOW_WORDS)
        for (token in window) {
            val cleaned = token.trim { c -> !c.isLetter() && c != '\'' }
            if (cleaned.isEmpty()) continue
            if (cleaned in negationCues || cleaned.endsWith("n't")) {
                return NegationFinding(negated = true, cue = cleaned)
            }
        }
        return NegationFinding(negated = false)
    }

    private fun lastSentenceBoundary(text: String, before: Int): Int {
        var i = before - 1
        while (i >= 0) {
            if (text[i] == '.' || text[i] == '!' || text[i] == '?') return i + 1
            i--
        }
        return 0
    }
}
