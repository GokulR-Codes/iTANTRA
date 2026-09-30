package com.itantra.alert

/**
 * HEMIM priority scale (master prompt §8).
 *
 * These are *initial policy candidates* for a hackathon-stage research system, not a
 * clinically or legally validated triage standard (§8, §15: "treat them as configurable
 * and testable rather than scientifically validated emergency standards"). [ordinal] order
 * is meaningful (P0 is most urgent) and is relied on by [EmergencyPolicy] and, eventually,
 * the P1 queue-priority mapping described in §17 — do not reorder without checking usages.
 *
 * [UNKNOWN] is a first-class value, not a fallback for "didn't classify". A message that
 * cannot be confidently classified must surface as [UNKNOWN] with `requiresReview = true`
 * on its [EmergencyAnalysis], never be silently dropped or silently downgraded to [P4]
 * (§8: "Do not silently discard UNKNOWN messages").
 */
enum class EmergencyPriority {
    /** Possible immediate threat to life. */
    P0,

    /** Serious emergency requiring assistance. */
    P1,

    /** Safety warning or evacuation notice. */
    P2,

    /** Non-critical assistance request. */
    P3,

    /** Normal, non-emergency communication. */
    P4,

    /** Insufficient evidence or uncertain classification — must not be discarded. */
    UNKNOWN
}
