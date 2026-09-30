package com.itantra.alert

/**
 * HEMIM (Hybrid Emergency Message Intelligence Model) category taxonomy.
 *
 * This is the V1 baseline category set (master prompt §7). It is deliberately a closed
 * enum rather than a free-text tag *for now* — Kotlin `when` exhaustiveness on this enum
 * is what lets [com.itantra.alert.EmergencyPolicy] and the Android UI layer be compile-time
 * checked against "did I handle every category". Extensibility is achieved by adding new
 * enum entries (a reviewed, deliberate change) plus a matching rule set in
 * [EmergencyRuleRegistry] and a matching entry in [EmergencyPolicy]'s base-priority table —
 * not by scattering ad-hoc string categories across modules (§7: "Do not hardcode category
 * logic across unrelated modules").
 */
enum class EmergencyCategory {
    MEDICAL,
    FIRE,
    FLOOD,
    ACCIDENT,
    TRAPPED_PERSON,
    NATURAL_DISASTER,
    SECURITY_THREAT,
    EVACUATION,
    GENERAL_ASSISTANCE,
    NORMAL_MESSAGE,

    /** Insufficient or contradictory evidence — never silently coerced to another category. */
    UNKNOWN
}
