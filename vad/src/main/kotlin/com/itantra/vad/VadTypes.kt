package com.itantra.vad

import com.itantra.core.AudioFrame

/** Result of a single VAD evaluation over one audio frame (typically 20-30ms @ 16kHz). */
data class VadResult(
    val isSpeech: Boolean,
    val speechProbability: Float,
    val energyDb: Float
)

interface VoiceActivityDetector {
    fun processFrame(frame: AudioFrame): VadResult
    fun reset()
}
