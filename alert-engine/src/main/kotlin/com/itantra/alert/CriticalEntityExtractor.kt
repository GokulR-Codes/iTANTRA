package com.itantra.alert

/** Extensible interface so a future ML-based extractor (HEMIM V4+) can be swapped in behind
 *  the same contract without touching [RuleBasedEmergencyAnalyzer] (master prompt §12). */
interface CriticalEntityExtractor {
    /** @param text the *original*, unmodified text — extraction runs its own internal
     *  case-insensitive matching and reports offsets into this exact string. */
    fun extract(text: String): List<CriticalEntity>
}

/**
 * Deterministic, regex/keyword-based critical entity extractor (master prompt §12, HEMIM
 * V2). English only in this version — see [EmergencyRuleRegistry] for the same scoping
 * decision and rationale.
 *
 * Explicitly documented limitations (§12: "Do not claim that regex-based extraction is
 * universally reliable"):
 *  - [CriticalEntityType.LOCATION] extraction (the phrase after "near"/"at"/"in"/"on") is a
 *    shallow prepositional-phrase grab. It over-extracts on non-locative uses of those
 *    prepositions ("trapped in the car" vs. "arrived at 3pm") and under-extracts multi-clause
 *    locations. It is a best-effort signal for a human reviewer, not a geocoding result.
 *  - [CriticalEntityType.PERSON_COUNT] only recognizes digits and the number words
 *    one..ten, not larger numbers written as words, and does not handle ranges ("two or
 *    three people").
 *  - Entities do not themselves carry negation/question/historical status — that context
 *    lives on the owning [EmergencyAnalysis]/[EmergencySignal], not per-entity, in this
 *    version (see `docs/HEMIM.md`).
 */
class RegexCriticalEntityExtractor : CriticalEntityExtractor {

    private data class KeywordEntity(val type: CriticalEntityType, val pattern: Regex)

    private val numberWords = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10"
    )

    private val personCountPattern = Regex(
        "\\b(\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\s+" +
            "(people|person|persons|men|women|children|kids|passengers|victims|survivors)\\b",
        RegexOption.IGNORE_CASE
    )

    private val timePattern = Regex(
        "\\b(\\d{1,2}:\\d{2}|now|just now|today|yesterday|\\d+\\s+(minutes?|hours?)\\s+ago)\\b",
        RegexOption.IGNORE_CASE
    )

    private val locationPattern = Regex(
        "\\b(?:near|at|on|behind|beside|under|beneath)\\s+(the\\s+)?([a-zA-Z][a-zA-Z '-]{2,40}?)(?=[.,!?]|\\s+(?:and|please|now)\\b|$)",
        RegexOption.IGNORE_CASE
    )

    private val directionPattern = Regex(
        "\\b(north|south|east|west|northeast|northwest|southeast|southwest|left|right|upstairs|downstairs)\\b",
        RegexOption.IGNORE_CASE
    )

    private val injuryKeywords = listOf(
        "unconscious", "bleeding", "severe bleeding", "broken leg", "broken arm", "fractured",
        "burn", "burns", "burned", "not breathing", "cannot breathe", "can't breathe", "injured"
    )

    private val medicalNeedKeywords = listOf(
        "ambulance", "doctor", "medicine", "oxygen", "first aid", "medical help", "paramedics"
    )

    private val severityKeywords = listOf(
        "critical", "severe", "serious", "major", "minor", "life-threatening"
    )

    private val requestedAssistanceKeywords = listOf(
        "help", "rescue", "ambulance", "fire brigade", "police", "send help"
    )

    private val eventKeywords: Map<String, EmergencyCategory> = mapOf(
        "trapped" to EmergencyCategory.TRAPPED_PERSON,
        "fire" to EmergencyCategory.FIRE,
        "flood" to EmergencyCategory.FLOOD,
        "accident" to EmergencyCategory.ACCIDENT,
        "collapsed" to EmergencyCategory.NATURAL_DISASTER,
        "earthquake" to EmergencyCategory.NATURAL_DISASTER
    )

    override fun extract(text: String): List<CriticalEntity> {
        val entities = mutableListOf<CriticalEntity>()

        Regex("\\b(${eventKeywords.keys.joinToString("|") { Regex.escape(it) }})\\b", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { m -> entities += CriticalEntity(CriticalEntityType.EVENT, m.value, m.range.first, m.range.last + 1) }

        personCountPattern.findAll(text).forEach { m ->
            val rawNumber = m.groupValues[1].lowercase()
            val normalizedNumber = numberWords[rawNumber] ?: rawNumber
            entities += CriticalEntity(CriticalEntityType.PERSON_COUNT, "$normalizedNumber ${m.groupValues[2]}", m.range.first, m.range.last + 1)
        }

        locationPattern.find(text)?.let { m ->
            val phrase = m.groupValues[2].trim()
            if (phrase.isNotEmpty()) {
                entities += CriticalEntity(CriticalEntityType.LOCATION, phrase, m.range.first, m.range.last + 1)
            }
        }

        timePattern.findAll(text).forEach { m -> entities += CriticalEntity(CriticalEntityType.TIME, m.value, m.range.first, m.range.last + 1) }
        directionPattern.findAll(text).forEach { m -> entities += CriticalEntity(CriticalEntityType.DIRECTION, m.value, m.range.first, m.range.last + 1) }

        addKeywordHits(text, injuryKeywords, CriticalEntityType.INJURY, entities)
        addKeywordHits(text, medicalNeedKeywords, CriticalEntityType.MEDICAL_NEED, entities)
        addKeywordHits(text, severityKeywords, CriticalEntityType.SEVERITY, entities)
        addKeywordHits(text, requestedAssistanceKeywords, CriticalEntityType.REQUESTED_ASSISTANCE, entities)

        return entities
    }

    private fun addKeywordHits(text: String, keywords: List<String>, type: CriticalEntityType, out: MutableList<CriticalEntity>) {
        val alreadyCoveredRanges = out.filter { it.type == type }.mapNotNull { e -> e.startIndex?.let { s -> s until (e.endIndex ?: s) } }
        for (keyword in keywords) {
            val pattern = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
            pattern.findAll(text).forEach { m ->
                val overlapsExisting = alreadyCoveredRanges.any { it.first < m.range.last + 1 && m.range.first < it.last + 1 }
                if (!overlapsExisting) {
                    out += CriticalEntity(type, m.value, m.range.first, m.range.last + 1)
                }
            }
        }
    }
}
