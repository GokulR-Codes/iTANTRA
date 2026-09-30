package com.itantra.languageid

import com.itantra.core.AudioFrame
import com.itantra.core.Language

/**
 * §19 (PS-derived clarification): "Language routing is explicit-selection
 * -first ... implement optional LID only if you have CPU budget left after
 * the required path is solid, and never let a low-confidence LID silently
 * override the user's explicit language setting."
 */
interface LanguageIdentifier {
    /** Call only after >= minSpeechMsBeforeLid of audio has accumulated (§13: 0.8-1.5s [TARGET]). */
    fun identify(frames: List<AudioFrame>): LidResult
}

data class LidResult(val language: Language, val confidence: Float)

/** [OPEN]: no audio LID backend selected/benchmarked yet — see docs/TODO.md. */
class UnimplementedLanguageIdentifier : LanguageIdentifier {
    override fun identify(frames: List<AudioFrame>): LidResult {
        throw NotImplementedError(
            "Audio LID backend not selected/benchmarked yet (§9, §18). " +
                "LanguageRouter must stay in explicit-selection mode until this lands."
        )
    }
}
