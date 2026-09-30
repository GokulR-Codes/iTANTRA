package com.itantra.alert

/**
 * One entry in the offline emergency phrase registry (master prompt §10).
 *
 * [pattern] is matched against normalized text (see [TextNormalizer]). [languageCode] uses
 * [com.itantra.core.Language.code] values (e.g. "en"); `null` means language-agnostic
 * (rare — mostly numerals/symbols would qualify, not phrases). [version] exists so a rule
 * can be revised in place while old [EmergencyAnalysis] records that cite it (via
 * [id]) remain attributable to the rule version that actually ran (§10: "Rule versioning if
 * practical").
 */
data class EmergencyRule(
    val id: String,
    val category: EmergencyCategory,
    val pattern: Regex,
    val weight: Double,
    val languageCode: String?,
    val description: String,
    val version: Int = 1
) {
    init {
        require(weight > 0.0) { "EmergencyRule weight must be positive, got $weight for rule $id" }
    }
}

/**
 * The offline, in-memory emergency phrase registry.
 *
 * V1-V3 scope (master prompt §32 Priority 0): English only. The registry shape (keyed by
 * language code, [rulesFor] taking a language parameter) is designed so additional
 * languages are added by appending rule lists, never by editing
 * [RuleBasedEmergencyAnalyzer]'s matching logic (§10: "additional languages can be added
 * without rewriting the analyzer") — that expansion is HEMIM V6 (§5), explicitly deferred,
 * not attempted here.
 *
 * Weights are hand-authored starting points, not learned or calibrated (§13, §14) — see
 * [ConfidenceFusion] for how they combine into a confidence estimate, and
 * `docs/HEMIM.md` for the full limitations note.
 */
object EmergencyRuleRegistry {

    private fun rule(id: String, category: EmergencyCategory, weight: Double, description: String, vararg phrases: String): List<EmergencyRule> {
        // Each phrase becomes its own rule instance sharing the same category/weight/description
        // family but a distinct id (id-1, id-2, ...) and its own compiled pattern, so a single
        // matched phrase can be cited exactly in EmergencySignal.matchedText/explanation.
        return phrases.mapIndexed { index, phrase ->
            EmergencyRule(
                id = "$id-${index + 1}",
                category = category,
                pattern = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE),
                weight = weight,
                languageCode = "en",
                description = description
            )
        }
    }

    private val englishRules: List<EmergencyRule> = buildList {
        addAll(rule("fire", EmergencyCategory.FIRE, 3.0, "Direct fire report",
            "fire", "on fire", "burning", "smoke everywhere", "building is burning"))
        addAll(rule("flood", EmergencyCategory.FLOOD, 3.0, "Direct flood report",
            "flood", "flooding", "water is rising", "flash flood"))
        addAll(rule("accident", EmergencyCategory.ACCIDENT, 2.5, "Vehicle/incident accident report",
            "accident", "car crash", "collision", "vehicle crash", "hit by a car"))
        addAll(rule("trapped", EmergencyCategory.TRAPPED_PERSON, 3.5, "Person(s) reported trapped",
            "trapped", "stuck under", "can't get out", "cannot get out", "pinned under", "buried under rubble"))
        addAll(rule("natural_disaster", EmergencyCategory.NATURAL_DISASTER, 3.0, "Natural disaster report",
            "earthquake", "landslide", "cyclone", "tsunami", "building collapsed", "building collapse"))
        addAll(rule("security", EmergencyCategory.SECURITY_THREAT, 3.5, "Security/violence threat report",
            "gunshots", "armed man", "someone has a knife", "being attacked", "intruder", "explosion"))
        addAll(rule("evacuation", EmergencyCategory.EVACUATION, 2.5, "Evacuation notice or request",
            "evacuate", "evacuation", "leave the area", "get out now"))
        addAll(rule("medical", EmergencyCategory.MEDICAL, 3.0, "Medical emergency report",
            "heart attack", "not breathing", "unconscious", "severe bleeding", "seizure", "can't breathe", "cannot breathe"))
        addAll(rule("general_assistance", EmergencyCategory.GENERAL_ASSISTANCE, 1.5, "Non-critical assistance request",
            "need help", "need assistance", "please help", "send help"))
    }

    /** Returns the rule set for [languageCode] (e.g. "en"). Returns an empty list — never
     *  throws and never silently falls back to a different language's rules — for any
     *  language without a rule set yet, so callers can detect and surface the gap
     *  explicitly rather than getting wrong-language matches. */
    fun rulesFor(languageCode: String?): List<EmergencyRule> = when (languageCode?.lowercase()) {
        "en", null -> englishRules
        else -> emptyList()
    }

    /** All rules across all languages currently registered — mainly for tests/tooling. */
    fun allRules(): List<EmergencyRule> = englishRules
}
