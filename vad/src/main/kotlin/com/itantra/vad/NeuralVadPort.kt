package com.itantra.vad

import com.itantra.core.AudioFrame

/**
 * Stage 2 VAD port. §18/§19: "a small neural VAD only around ambiguous
 * frames" (Silero-VAD-class candidate in §18's VAD matrix — MIT-licensed,
 * sub-1MB, per its own published benchmarks, which §18 explicitly says to
 * re-verify on a genuine Class L device rather than trust as given).
 *
 * [OPEN]: no model backend is wired up in this session. A real
 * implementation needs an ONNX Runtime Mobile / LiteRT dependency plus the
 * Silero weights, both requiring §9's license/size/latency verification —
 * that is Android/ML-runtime-dependent work, out of scope for this
 * pure-JVM pass, and is tracked in docs/TODO.md.
 *
 * TwoStageVad depends on this interface, not a concrete model, so a real
 * backend can be dropped in later without touching endpointing logic.
 */
interface NeuralVad {
    fun scoreSpeechProbability(frame: AudioFrame): Float
    fun reset()
}

/** Deterministic stand-in used only to unit-test TwoStageVad's wake/sleep logic. */
class StubNeuralVad(private val fixedProbability: Float = 0.5f) : NeuralVad {
    override fun scoreSpeechProbability(frame: AudioFrame): Float = fixedProbability
    override fun reset() {}
}
