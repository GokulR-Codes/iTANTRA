package com.itantra.alert

/**
 * Combined context finding for one matched phrase (master prompt §11).
 *
 * None of these flags are mutually exclusive detectors of "truth" — they are independent
 * heuristic cues that [RuleBasedEmergencyAnalyzer] combines conservatively: when any of
 * them fire, the analyzer prefers caution (lower priority, `requiresReview = true`) over
 * confidently asserting an active emergency (§11: "Avoid confidently assigning a dangerous
 * classification").
 */
data class ContextFinding(
    val negated: Boolean,
    val negationCue: String?,
    val isQuestion: Boolean,
    val isHistorical: Boolean,
    val historicalCue: String?,
    val isResolved: Boolean,
    val resolvedCue: String?
)

/**
 * Rule-based context analysis: negation (delegated to [NegationDetector]), question form,
 * historical/reported framing, and resolved-event framing (master prompt §11).
 *
 * Like [NegationDetector], this is a curated-keyword heuristic, not a parser — see the
 * cue lists below and `docs/HEMIM.md` for the documented false-negative/false-positive
 * risks (e.g. "was" alone is deliberately *not* treated as a historical cue because it is
 * far too common in non-historical constructions; only more specific reporting/time phrases
 * are used).
 */
object ContextAnalyzer {

    private val historicalCues = listOf(
        "yesterday", "last night", "last week", "earlier today", "a while ago", "minutes ago",
        "hours ago", "days ago", "was reported", "were reported", "someone reported", "reported that",
        "already resolved", "used to be"
    )

    private val resolvedCues = listOf(
        "extinguished", "put out", "under control", "contained", "rescued", "resolved",
        "no longer", "safe now", "all clear", "situation is over"
    )

    private val interrogativeStarters = setOf(
        "is", "are", "was", "were", "do", "does", "did", "can", "could", "would", "has", "have",
        "what", "where", "when", "who", "why", "how"
    )

    fun analyze(normalizedText: String, matchStart: Int, matchEnd: Int): ContextFinding {
        val negation = NegationDetector.findNegation(normalizedText, matchStart)

        val (sentenceStart, sentenceEnd) = sentenceBounds(normalizedText, matchStart)
        val sentence = normalizedText.substring(sentenceStart, sentenceEnd)

        val isQuestion = sentence.contains("?") ||
            sentence.trim().split(Regex("\\s+")).firstOrNull() in interrogativeStarters

        val historicalHit = historicalCues.firstOrNull { sentence.contains(it) }
        val resolvedHit = resolvedCues.firstOrNull { sentence.contains(it) }

        return ContextFinding(
            negated = negation.negated,
            negationCue = negation.cue,
            isQuestion = isQuestion,
            isHistorical = historicalHit != null,
            historicalCue = historicalHit,
            isResolved = resolvedHit != null,
            resolvedCue = resolvedHit
        )
    }

    private fun sentenceBounds(text: String, around: Int): Pair<Int, Int> {
        var start = around
        while (start > 0 && text[start - 1] !in ".!?") start--
        var end = around
        while (end < text.length && text[end] !in ".!?") end++
        return start to (end + 1).coerceAtMost(text.length)
    }
}
