package com.itantra.languageid

import com.itantra.core.Language

/**
 * §19: explicit selection is the primary mode. Auto-LID, if enabled, may
 * only update the active language when confidence clears
 * [lidConfidenceFloor] — otherwise the previous (explicit or last-confirmed)
 * language is kept, never silently overridden.
 */
class LanguageRouter(
    initialLanguage: Language,
    private val lidEnabled: Boolean = false,
    private val lidConfidenceFloor: Float = 0.7f, // [TARGET] — tune against §33's noise sweep
    @Suppress("unused") private val lid: LanguageIdentifier? = null
) {
    var activeLanguage: Language = initialLanguage
        private set

    /** User explicitly changes language — always wins (§19). */
    fun setExplicitLanguage(language: Language) {
        activeLanguage = language
    }

    /** Offers a LID result; applied only if LID is enabled and confidence clears the floor. Returns whether it was applied. */
    fun offerLidResult(result: LidResult): Boolean {
        if (!lidEnabled) return false
        if (result.confidence < lidConfidenceFloor) return false
        activeLanguage = result.language
        return true
    }
}
