package com.itantra.alert

/** Where a piece of evidence for a classification came from. V1 only produces [RULE],
 *  [CONTEXT], and [ENTITY]; [ML_CLASSIFIER] is reserved for HEMIM V4 (§14) and must never
 *  be emitted until a real classifier exists behind it. */
enum class EmergencySignalSource { RULE, CONTEXT, ENTITY, ML_CLASSIFIER }

/**
 * One piece of evidence contributing to an [EmergencyAnalysis] (master prompt §9).
 *
 * [weight] is a raw, uncalibrated rule weight, not a probability — see
 * [EmergencyPolicy]'s and [ConfidenceFusion]'s doc comments for why these must never be
 * treated as calibrated confidence values on their own (§13: "Avoid treating arbitrary
 * weights as calibrated probabilities").
 */
data class EmergencySignal(
    val category: EmergencyCategory,
    val matchedText: String,
    val weight: Double,
    val source: EmergencySignalSource,
    val explanation: String,
    /** True if context analysis (negation, question form, historical framing — §11)
     *  determined this signal should not be read as an active, present-tense emergency. */
    val suppressedByContext: Boolean = false
) {
    init {
        require(weight >= 0.0) { "EmergencySignal.weight must be non-negative, got $weight (use suppressedByContext to down-weight, not a negative weight)" }
    }
}
