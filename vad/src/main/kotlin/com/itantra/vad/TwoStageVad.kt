package com.itantra.vad

import com.itantra.core.AudioFrame

/**
 * §19: "Stage 1 (WebRTC-VAD-class energy gate) runs continuously at
 * near-zero cost; Stage 2 (Silero-class neural VAD) wakes only near
 * ambiguous boundaries the Stage-1 gate flags."
 *
 * "Ambiguous" is defined here as a Stage-1 probability inside
 * [ambiguousLow, ambiguousHigh]; comfortably-speech or comfortably-silence
 * frames never wake Stage 2. The exact band is [TARGET] — it needs tuning
 * once a real Stage-2 backend and a labeled noise-condition eval set exist
 * (§34), not left at these starting numbers by default.
 */
class TwoStageVad(
    private val stage1: VoiceActivityDetector = EnergyGateVad(),
    private val stage2: NeuralVad,
    private val ambiguousLow: Float = 0.35f,
    private val ambiguousHigh: Float = 0.65f
) {
    var stage2WakeCount: Int = 0
        private set

    fun processFrame(frame: AudioFrame): VadResult {
        val r1 = stage1.processFrame(frame)
        if (r1.speechProbability in ambiguousLow..ambiguousHigh) {
            stage2WakeCount++
            val p2 = stage2.scoreSpeechProbability(frame)
            return r1.copy(isSpeech = p2 >= 0.5f, speechProbability = p2)
        }
        return r1
    }

    fun reset() {
        stage1.reset()
        stage2.reset()
        stage2WakeCount = 0
    }
}
