package com.itantra.alert

/** Entity types the V1 extractor targets (master prompt §12). */
enum class CriticalEntityType {
    EVENT,
    LOCATION,
    PERSON_COUNT,
    INJURY,
    MEDICAL_NEED,
    SEVERITY,
    TIME,
    DIRECTION,
    REQUESTED_ASSISTANCE
}

/**
 * One extracted critical entity (master prompt §9, §12).
 *
 * [startIndex]/[endIndex] are character offsets into the *original, unmodified* text
 * ([EmergencyAnalysis.originalText]), not into any normalized copy, so a UI can highlight
 * the exact span. [confidence] is nullable because the V1 deterministic/regex extractor is
 * pattern-hit-or-miss, not a scored model (§12: "Do not claim that regex-based extraction
 * is universally reliable") — a `null` confidence must be treated as "extractor does not
 * self-score", not silently defaulted to a numeric value.
 */
data class CriticalEntity(
    val type: CriticalEntityType,
    val value: String,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
    val confidence: Double? = null
) {
    init {
        if (startIndex != null && endIndex != null) {
            require(endIndex >= startIndex) { "CriticalEntity endIndex ($endIndex) must be >= startIndex ($startIndex)" }
        }
        confidence?.let { require(it in 0.0..1.0) { "CriticalEntity.confidence must be in [0,1], got $it" } }
    }
}
