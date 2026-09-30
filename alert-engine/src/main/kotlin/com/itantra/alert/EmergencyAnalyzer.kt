package com.itantra.alert

/**
 * HEMIM's top-level analysis contract (master prompt §5, §9).
 *
 * Implementations must never throw on ordinary input — malformed, empty, extremely long,
 * or foreign-language text must still produce an [EmergencyAnalysis] (category UNKNOWN if
 * nothing else fits), never an exception that would cause a caller to drop the message.
 */
interface EmergencyAnalyzer {
    /**
     * @param text the raw message text, exactly as received. Never mutated.
     * @param languageCode optional [com.itantra.core.Language.code] (e.g. "en"); null means
     *   unknown/unset. If no rule set exists for the given language, the result is
     *   category/priority UNKNOWN with `requiresReview = true`, not a wrong-language guess.
     */
    fun analyze(text: String, languageCode: String? = null): EmergencyAnalysis
}
