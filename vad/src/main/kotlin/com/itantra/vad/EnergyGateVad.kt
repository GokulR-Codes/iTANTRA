package com.itantra.vad

import com.itantra.core.AudioFrame
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stage 1 VAD. [BLUEPRINT-ADOPT] §18/§19: "a very cheap energy/noise-adaptive
 * gate on every frame ... runs continuously at near-zero cost."
 *
 * This is a real, dependency-free adaptive energy-threshold gate, in the
 * spirit of the WebRTC-VAD-class Stage 1 candidate in §18 — it is NOT a
 * claim of bit-exact WebRTC VAD parity (porting/benchmarking the actual
 * WebRTC GMM VAD is [OPEN], tracked in docs/TODO.md). It exists so
 * TwoStageVad has a real, testable Stage 1 today, with Stage 2 (Silero-class
 * neural VAD, see NeuralVadPort.kt) still pending model integration.
 *
 * Algorithm: track a slowly-adapting noise floor (dB) via an EWMA computed
 * only over frames classified as non-speech, so a long speech segment can't
 * drag the floor upward. A frame is flagged speech when its energy exceeds
 * noiseFloorDb + marginDb.
 */
class EnergyGateVad(
    private val marginDb: Float = 12f,
    private val noiseFloorAdaptRate: Float = 0.05f,
    private val initialNoiseFloorDb: Float = -50f
) : VoiceActivityDetector {

    private var noiseFloorDb: Float = initialNoiseFloorDb

    override fun processFrame(frame: AudioFrame): VadResult {
        val energyDb = rmsEnergyDb(frame.pcm16)
        val isSpeech = energyDb > noiseFloorDb + marginDb

        if (!isSpeech) {
            noiseFloorDb = (1 - noiseFloorAdaptRate) * noiseFloorDb + noiseFloorAdaptRate * energyDb
        }

        // Cheap, bounded [0,1] confidence proxy for TwoStageVad's ambiguity
        // band — NOT a calibrated probability. Calibration requires a real
        // labeled eval set (§34), not asserted here.
        val probability = min(1f, max(0f, (energyDb - noiseFloorDb) / (marginDb * 2f)))

        return VadResult(isSpeech = isSpeech, speechProbability = probability, energyDb = energyDb)
    }

    override fun reset() {
        noiseFloorDb = initialNoiseFloorDb
    }

    companion object {
        fun rmsEnergyDb(pcm16: ShortArray): Float {
            if (pcm16.isEmpty()) return -120f
            var sumSquares = 0.0
            for (sample in pcm16) {
                val normalized = sample / 32768.0
                sumSquares += normalized * normalized
            }
            val rms = sqrt(sumSquares / pcm16.size)
            val floor = 1e-9
            return (20.0 * log10(max(rms, floor))).toFloat()
        }
    }
}
