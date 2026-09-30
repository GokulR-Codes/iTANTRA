package com.itantra.alert

/**
 * Turns raw [EmergencySignal] weights into a bounded, deterministic confidence estimate
 * (master prompt §13).
 *
 * This is a heuristic normalization, explicitly **not** a calibrated probability (§8, §13:
 * "Avoid treating arbitrary weights as calibrated probabilities"). The same input always
 * produces the same output (§13: "Use deterministic behavior for identical inputs") — there
 * is no randomness or hidden state anywhere in this object.
 *
 * [CALIBRATION_CONSTANT] is a hand-picked normalization divisor, not a fitted parameter —
 * it exists so a single strong signal (e.g. one weight-3.5 rule match) lands around
 * ~0.6-0.7 confidence and two or more corroborating signals push toward the 0.9+ range,
 * which was judged to feel reasonable during authoring. It has not been fitted or
 * validated against a labeled dataset (HEMIM V4/V6 territory, §14/§27) and must not be
 * cited as such.
 */
object ConfidenceFusion {
    private const val CALIBRATION_CONSTANT = 5.0
    private const val QUESTION_PENALTY = 0.5
    private const val HISTORICAL_PENALTY = 0.7

    /**
     * @param activeWeightSum sum of [EmergencySignal.weight] for signals that were NOT
     *   suppressed by negation/resolution.
     * @param allActiveAreQuestions true if every contributing active signal occurred in a
     *   question-form sentence.
     * @param allActiveAreHistorical true if every contributing active signal occurred in a
     *   historical/reported-framing sentence (and none were questions).
     */
    fun fuse(activeWeightSum: Double, allActiveAreQuestions: Boolean, allActiveAreHistorical: Boolean): Double {
        if (activeWeightSum <= 0.0) return 0.0
        var confidence = (activeWeightSum / CALIBRATION_CONSTANT).coerceIn(0.0, 1.0)
        if (allActiveAreQuestions) confidence *= QUESTION_PENALTY
        else if (allActiveAreHistorical) confidence *= HISTORICAL_PENALTY
        return confidence.coerceIn(0.0, 1.0)
    }
}
