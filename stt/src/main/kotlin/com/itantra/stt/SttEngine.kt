package com.itantra.stt

import com.itantra.core.AudioFrame
import com.itantra.core.Language
import com.itantra.core.RecognizedUtterance

/**
 * Streaming STT engine contract (§18, §19). Implementations are expected to
 * emit partial hypotheses continuously and mark exactly one final hypothesis
 * per utterance, per the endpointer's FINALIZING transition (core's
 * EndpointStateMachine) — this interface does not do endpointing itself.
 *
 * No real implementation exists yet. Candidate model selection must follow
 * §9's Research Methodology and produce a filled-in Model Scorecard (§18)
 * before any class implementing this interface is wired into production —
 * see §18's STT candidate matrix (IndicConformer, sherpa-onnx, Vakyansh,
 * Whisper-as-oracle-only) for the currently tracked options.
 *
 * INTEGRATION NOTE (see docs/FILE_CONFLICT_MATRIX.md row "SttEngine"): the
 * Part III speech/language stack defined a second, incompatible interface
 * (`acceptFrame`/`finalize`/`reset`, no model-lifecycle methods) alongside a
 * standalone `SimulatedStreamingSttAdapter` that didn't even implement it.
 * This interface — the Gate1 git archive's version — is canonical because it
 * models model load/unload, which `LanguageRegistry` (core.registry) and the
 * ten-language switching requirement (§21) genuinely need. Part III's
 * simulation is not discarded: `SimulationBackedSttEngine` in this module
 * wraps it so it can be plugged in wherever a real `SttEngine` is expected —
 * for local pipeline wiring and demos ONLY. It is explicitly not offline
 * speech recognition; see that class's KDoc before using it for anything
 * that could be read as a "STT works" claim.
 */
interface SttEngine {
    val activeLanguage: Language

    /** Feed one 16kHz mono frame; returns a partial or final hypothesis, or null if nothing new. */
    fun onFrame(frame: AudioFrame): RecognizedUtterance?

    /** Real-time factor of the last processed segment: processing_time / audio_duration. Must be < 1 on Class L (§18). */
    fun lastRtf(): Double?

    fun loadLanguage(language: Language)
    fun unloadLanguage()
}

/**
 * Placeholder that fails loudly rather than fabricating a transcript. Exists
 * so Division C/D can wire the app shell and protocol layers against a real
 * [SttEngine] instance today, and swap this for a genuine model-backed
 * implementation without changing any call site.
 */
class UnimplementedSttEngine(override val activeLanguage: Language) : SttEngine {
    override fun onFrame(frame: AudioFrame): RecognizedUtterance? {
        throw NotImplementedError(
            "No STT model is wired in yet (Gate 1 skeleton only). See §18's candidate " +
                "matrix; adoption requires a filled Model Scorecard, not this stub."
        )
    }

    override fun lastRtf(): Double? = null
    override fun loadLanguage(language: Language) {
        throw NotImplementedError("UnimplementedSttEngine cannot load a language — no model backs it.")
    }
    override fun unloadLanguage() { /* no-op: nothing was ever loaded */ }
}
