package com.itantra.alert

/**
 * HEMIM V1 + V2 + V3: rule-based emergency detection, critical entity extraction, and
 * negation/context handling combined (master prompt §5, §10, §11, §12).
 *
 * Deliberately does **not** contain any ML classifier, evidence-fusion-across-multiple-
 * model-types logic, or multilingual rule content beyond English — those are HEMIM V4/V5/V6
 * (§5, §14), out of scope here and not silently approximated. See `docs/HEMIM.md` for the
 * full write-up, `docs/PENDING_WORK_MASTER.md` P0/P1 for what comes next, and
 * [EmergencyAnalyzerFactory] for how to obtain an instance.
 *
 * Algorithm summary (see inline comments for the exact decision points):
 *  1. Normalize text, match every rule in [EmergencyRuleRegistry] against it.
 *  2. For every match, run [ContextAnalyzer] (negation, question form, historical framing,
 *     resolved-event framing) on the sentence the match occurred in.
 *  3. Signals negated or marked resolved are recorded (never dropped from
 *     [EmergencyAnalysis.detectedSignals]) but excluded from category/priority scoring.
 *  4. Among the remaining ("active") signals, the highest-total-weight category wins.
 *  5. If every active signal for that category was a question, priority becomes UNKNOWN.
 *     If every active signal was historically framed (and none were questions), priority is
 *     capped at P2. Otherwise [EmergencyPolicy] assigns the priority.
 *  6. [CriticalEntityExtractor] runs independently over the original text and its output is
 *     attached regardless of the category outcome.
 */
class RuleBasedEmergencyAnalyzer(
    private val entityExtractor: CriticalEntityExtractor = RegexCriticalEntityExtractor()
) : EmergencyAnalyzer {

    private data class ActiveMatch(val signal: EmergencySignal, val context: ContextFinding)

    override fun analyze(text: String, languageCode: String?): EmergencyAnalysis {
        val now = System.currentTimeMillis()

        if (text.isBlank()) {
            return EmergencyAnalysis(
                originalText = text,
                language = languageCode,
                category = EmergencyCategory.UNKNOWN,
                priority = EmergencyPriority.UNKNOWN,
                confidence = 0.0,
                detectedSignals = emptyList(),
                entities = emptyList(),
                isPotentialEmergency = false,
                requiresReview = true,
                reasons = listOf("input text is empty or blank; nothing to analyze"),
                timestampMs = now
            )
        }

        val rules = EmergencyRuleRegistry.rulesFor(languageCode)
        if (rules.isEmpty()) {
            return EmergencyAnalysis(
                originalText = text,
                language = languageCode,
                category = EmergencyCategory.UNKNOWN,
                priority = EmergencyPriority.UNKNOWN,
                confidence = 0.0,
                detectedSignals = emptyList(),
                entities = emptyList(),
                isPotentialEmergency = true,
                requiresReview = true,
                reasons = listOf(
                    "no HEMIM rule set is registered for language '${languageCode ?: "unknown"}' " +
                        "(V1-V3 ships English only); message preserved and flagged for manual review"
                ),
                timestampMs = now
            )
        }

        val normalized = TextNormalizer.normalize(text)
        val allSignals = mutableListOf<EmergencySignal>()
        val activeMatches = mutableListOf<ActiveMatch>()
        val reasons = mutableListOf<String>()

        for (rule in rules) {
            rule.pattern.findAll(normalized).forEach { match ->
                val context = ContextAnalyzer.analyze(normalized, match.range.first, match.range.last + 1)
                val suppressed = context.negated || context.isResolved
                val explanation = buildString {
                    append("matched \"${match.value}\" for ${rule.category} (rule ${rule.id}, weight ${rule.weight})")
                    if (context.negated) append(" — negated by \"${context.negationCue}\", excluded from scoring")
                    if (context.isResolved) append(" — framed as resolved (\"${context.resolvedCue}\"), excluded from scoring")
                    if (context.isQuestion) append(" — appears in a question")
                    if (context.isHistorical) append(" — framed historically/reported (\"${context.historicalCue}\")")
                }
                val signal = EmergencySignal(
                    category = rule.category,
                    matchedText = match.value,
                    weight = rule.weight,
                    source = EmergencySignalSource.RULE,
                    explanation = explanation,
                    suppressedByContext = suppressed
                )
                allSignals += signal
                reasons += explanation
                if (!suppressed) activeMatches += ActiveMatch(signal, context)
            }
        }

        val entities = entityExtractor.extract(text)

        if (allSignals.isEmpty()) {
            return EmergencyAnalysis(
                originalText = text,
                language = languageCode,
                category = EmergencyCategory.NORMAL_MESSAGE,
                priority = EmergencyPriority.P4,
                confidence = 0.0,
                detectedSignals = emptyList(),
                entities = entities,
                isPotentialEmergency = false,
                requiresReview = false,
                reasons = listOf("no emergency phrases matched any registered rule"),
                timestampMs = now
            )
        }

        if (activeMatches.isEmpty()) {
            // Every match was negated or resolved: confirmed non-emergency, but a human
            // should still be able to see why (§11: "mark it for review where appropriate").
            reasons += "all matched emergency phrases were negated or framed as resolved; classified as non-emergency and flagged for review"
            return EmergencyAnalysis(
                originalText = text,
                language = languageCode,
                category = EmergencyCategory.NORMAL_MESSAGE,
                priority = EmergencyPriority.P4,
                confidence = 0.15,
                detectedSignals = allSignals,
                entities = entities,
                isPotentialEmergency = false,
                requiresReview = true,
                reasons = reasons,
                timestampMs = now
            )
        }

        val weightByCategory = activeMatches.groupBy { it.signal.category }
            .mapValues { (_, matches) -> matches.sumOf { it.signal.weight } }
        val topCategory = weightByCategory.entries.maxByOrNull { it.value }!!.key
        val topMatches = activeMatches.filter { it.signal.category == topCategory }
        val topWeightSum = topMatches.sumOf { it.signal.weight }

        val allQuestions = topMatches.all { it.context.isQuestion }
        val allHistorical = !allQuestions && topMatches.all { it.context.isHistorical }

        val priority = when {
            allQuestions -> {
                reasons += "every active signal for $topCategory occurred in a question, not a confirmed report; priority set to UNKNOWN pending confirmation"
                EmergencyPriority.UNKNOWN
            }
            allHistorical -> {
                val policyPriority = EmergencyPolicy.priorityFor(topCategory, entities)
                val capped = if (policyPriority.ordinal < EmergencyPriority.P2.ordinal) EmergencyPriority.P2 else policyPriority
                reasons += "every active signal for $topCategory was framed historically/as reported; priority capped at ${capped.name} pending confirmation the situation is still active"
                capped
            }
            else -> EmergencyPolicy.priorityFor(topCategory, entities)
        }

        val confidence = ConfidenceFusion.fuse(topWeightSum, allQuestions, allHistorical)
        val requiresReview = allQuestions || allHistorical || confidence < REVIEW_CONFIDENCE_THRESHOLD || priority == EmergencyPriority.UNKNOWN

        reasons += "classified as $topCategory / $priority from ${topMatches.size} active signal(s) totalling weight $topWeightSum"

        return EmergencyAnalysis(
            originalText = text,
            language = languageCode,
            category = topCategory,
            priority = priority,
            confidence = confidence,
            detectedSignals = allSignals,
            entities = entities,
            isPotentialEmergency = topCategory != EmergencyCategory.NORMAL_MESSAGE,
            requiresReview = requiresReview,
            reasons = reasons,
            timestampMs = now
        )
    }

    companion object {
        private const val REVIEW_CONFIDENCE_THRESHOLD = 0.4
    }
}
