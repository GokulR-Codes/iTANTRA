package com.itantra.alert

/**
 * The complete output of a HEMIM analysis pass (master prompt §8, §9).
 *
 * This is intentionally an over-complete record: every field the master prompt lists as
 * mandatory to preserve is present and non-optional-by-omission, because §8 requires "every
 * emergency analysis must preserve" all of these, and §20 requires the Android UI to never
 * "replace the original message with only a compressed form". Downstream code (UI, policy,
 * queueing) must read [priority]/[category] through this record, never re-derive them from
 * [originalText] independently — that would create two sources of truth.
 */
data class EmergencyAnalysis(
    /** The exact, unmodified input text. Never mutated, truncated, or replaced by a
     *  normalized/compressed form (§8, §20). */
    val originalText: String,
    /** BCP-47-ish or [com.itantra.core.Language] code string, or null if unknown/unset.
     *  V1-V3 only ship English rules (see [EmergencyRuleRegistry]); this field still exists
     *  so a caller can record what language the text actually was, even if no rule set for
     *  it exists yet (§10: multilingual extensibility without rewriting the analyzer). */
    val language: String?,
    val category: EmergencyCategory,
    val priority: EmergencyPriority,
    /** Heuristic, uncalibrated confidence in [0,1] — see [ConfidenceFusion]'s doc comment.
     *  Never treat this as a statistically calibrated probability (§8, §13). */
    val confidence: Double,
    val detectedSignals: List<EmergencySignal>,
    val entities: List<CriticalEntity>,
    /** True if [category] is anything other than NORMAL_MESSAGE and the analysis was not
     *  fully suppressed by context (negation/question/historical framing). This is a
     *  convenience flag derived at construction time from [category]/[priority]/context —
     *  it does not add new information beyond what [category]/[priority] already encode. */
    val isPotentialEmergency: Boolean,
    /** True if a human should look at this message before it is fully trusted: [priority]
     *  is UNKNOWN, context was ambiguous (negation/question/historical), or confidence is
     *  low. The message itself is still transmitted/preserved either way — this flag never
     *  causes a message to be dropped (§8, §17). */
    val requiresReview: Boolean,
    /** Human-readable reasons for the classification, in the order they were applied.
     *  Always non-empty — an analyzer that classifies something must be able to say why. */
    val reasons: List<String>,
    val timestampMs: Long
) {
    init {
        require(confidence in 0.0..1.0) { "EmergencyAnalysis.confidence must be in [0,1], got $confidence" }
        require(reasons.isNotEmpty()) { "EmergencyAnalysis.reasons must not be empty — every classification must be explainable" }
    }
}
