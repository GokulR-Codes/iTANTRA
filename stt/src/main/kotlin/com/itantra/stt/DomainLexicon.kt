package com.itantra.stt

import com.itantra.core.Language

/**
 * §19/§33: alert-relevant vocabulary the STT layer should bias decoding
 * toward, and whose recall is tracked as its OWN accuracy metric — "missing
 * an alert keyword is a worse failure than missing a filler word; measure
 * this specifically, don't just assume the general WER number covers it."
 *
 * The English seed set is [ASSUMPTION]. Per-language translations are
 * [OPEN] pending native-speaker review (§33's pronunciation/meaning audit
 * requirement) — do not ship a language as "alert-lexicon supported" (§46)
 * until its set is populated AND reviewed.
 */
object DomainLexicon {

    val alertKeywordsEn: Set<String> = setOf(
        "help", "fire", "flood", "earthquake", "medical", "evacuate",
        "emergency", "danger", "trapped", "injured", "ambulance", "rescue"
    )

    private val byLanguage: MutableMap<Language, Set<String>> =
        mutableMapOf(Language.EN to alertKeywordsEn)

    fun keywordsFor(language: Language): Set<String> = byLanguage[language] ?: emptySet()

    fun isPopulated(language: Language): Boolean = byLanguage[language]?.isNotEmpty() == true

    /** Registers/overwrites a native-speaker-reviewed keyword set for a language. */
    fun register(language: Language, keywords: Set<String>) {
        byLanguage[language] = keywords
    }

    /** Naive substring recall check — the real §33 metric needs a proper eval harness, this is the building block. */
    fun containsAnyKeyword(text: String, language: Language): Boolean {
        val lower = text.lowercase()
        return keywordsFor(language).any { lower.contains(it.lowercase()) }
    }
}
