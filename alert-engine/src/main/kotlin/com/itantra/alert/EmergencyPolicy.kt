package com.itantra.alert

/**
 * Converts a category + supporting entities into a baseline [EmergencyPriority] (master
 * prompt §15).
 *
 * This is the V1-V3 baseline policy only: a plain configurable lookup table plus a small
 * number of entity-informed escalation rules. It is explicitly **not** the full evidence-
 * fusion + uncertainty-aware policy engine described in §13/§15 for HEMIM V5 — this object
 * is the seed that engine will extend once the ML classifier (V4) exists, not a finished
 * replacement for it (§15: "initial policy candidates... make them configurable and
 * testable rather than treating them as scientifically validated emergency standards").
 *
 * The lookup table is a plain `val` map so it is trivially inspectable/testable, per §15's
 * "configurable and testable" mandate — a future config-driven version can replace the map
 * without changing this object's public shape.
 */
object EmergencyPolicy {

    /** §15's initial policy candidates, directly encoded. */
    private val basePriority: Map<EmergencyCategory, EmergencyPriority> = mapOf(
        EmergencyCategory.TRAPPED_PERSON to EmergencyPriority.P0,
        EmergencyCategory.FIRE to EmergencyPriority.P0,
        EmergencyCategory.SECURITY_THREAT to EmergencyPriority.P0,
        EmergencyCategory.MEDICAL to EmergencyPriority.P0,
        EmergencyCategory.FLOOD to EmergencyPriority.P1,
        EmergencyCategory.ACCIDENT to EmergencyPriority.P1,
        EmergencyCategory.NATURAL_DISASTER to EmergencyPriority.P1,
        EmergencyCategory.EVACUATION to EmergencyPriority.P2,
        EmergencyCategory.GENERAL_ASSISTANCE to EmergencyPriority.P3,
        EmergencyCategory.NORMAL_MESSAGE to EmergencyPriority.P4,
        EmergencyCategory.UNKNOWN to EmergencyPriority.UNKNOWN
    )

    /** Categories whose baseline priority is raised by one level (never above P0) when a
     *  SEVERITY entity with one of these values is also present — an example of the
     *  "consider critical entities" requirement in §15, deliberately narrow in scope for V1. */
    private val severityEscalatable = setOf(
        EmergencyCategory.FLOOD, EmergencyCategory.ACCIDENT, EmergencyCategory.NATURAL_DISASTER,
        EmergencyCategory.EVACUATION, EmergencyCategory.GENERAL_ASSISTANCE
    )
    private val escalatingSeverityValues = setOf("critical", "severe", "serious", "major", "life-threatening")

    fun basePriorityFor(category: EmergencyCategory): EmergencyPriority =
        basePriority[category] ?: EmergencyPriority.UNKNOWN

    /** Applies the (narrow, documented) entity-informed escalation on top of [basePriorityFor]. */
    fun priorityFor(category: EmergencyCategory, entities: List<CriticalEntity>): EmergencyPriority {
        val base = basePriorityFor(category)
        if (category !in severityEscalatable) return base
        val hasEscalatingSeverity = entities.any {
            it.type == CriticalEntityType.SEVERITY && it.value.lowercase() in escalatingSeverityValues
        }
        if (!hasEscalatingSeverity) return base
        val escalated = EmergencyPriority.entries.getOrNull(base.ordinal - 1)
        return escalated ?: base
    }
}
