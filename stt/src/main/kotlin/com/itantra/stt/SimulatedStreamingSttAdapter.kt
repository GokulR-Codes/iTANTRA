package com.itantra.stt

import com.itantra.core.AudioFrame
import com.itantra.core.RecognizedUtterance
import com.itantra.vad.EndpointEvent
import com.itantra.vad.Endpointer

/**
 * [BLUEPRINT-BENCHMARK] §18/§19: the sherpa-onnx-style "simulate streaming"
 * pattern — buffer VAD-segmented audio and feed the accumulated segment to a
 * (possibly non-streaming/offline) recognizer on each endpointer event,
 * rather than requiring the underlying model to be natively streaming.
 *
 * This class owns only the buffering/segmentation policy; the actual
 * recognition call is delegated to [OfflineRecognizer] so a real backend
 * (IndicConformer/sherpa-onnx, §18) can be dropped in without touching this
 * logic. Whether this interim path meets the P95 finalization-latency gate
 * (§34) is UNMEASURED — it must be benchmarked before being promoted out of
 * [BLUEPRINT-BENCHMARK] status, per the tag legend in §1.
 */
interface OfflineRecognizer {
    fun recognize(pcm16: ShortArray, sampleRateHz: Int, languageIso: String): String
}

class SimulatedStreamingSttAdapter(
    private val recognizer: OfflineRecognizer,
    private val endpointer: Endpointer,
    private val languageIso: String,
    private val emitPartialsEveryNFrames: Int = 10
) {
    private val buffer = ArrayList<Short>()
    private var frameCount = 0
    private var utteranceStartTsMs: Long = 0

    /** Returns a partial or final RecognizedUtterance, or null if there's nothing new to report. */
    fun onFrame(frame: AudioFrame, vadIsSpeech: Boolean): RecognizedUtterance? {
        val event = endpointer.onVadFrame(vadIsSpeech)

        if (vadIsSpeech) {
            buffer.ensureCapacity(buffer.size + frame.pcm16.size)
            for (s in frame.pcm16) buffer.add(s)
        }

        if (event is EndpointEvent.SpeechStarted) {
            utteranceStartTsMs = frame.captureTsNanos / 1_000_000
        }

        if (event is EndpointEvent.Finalized) {
            val text = if (buffer.isEmpty()) "" else recognizer.recognize(
                buffer.toShortArray(), frame.sampleRateHz, languageIso
            )
            val endTsMs = frame.captureTsNanos / 1_000_000
            val result = RecognizedUtterance(
                text = text,
                lang = languageIso,
                isFinal = true,
                confidence = 0f, // [OPEN]: confidence must come from the real backend, never fabricated here
                startTsMs = utteranceStartTsMs,
                endTsMs = endTsMs
            )
            buffer.clear()
            frameCount = 0
            endpointer.reset()
            return result
        }

        if (vadIsSpeech) {
            frameCount++
            if (frameCount % emitPartialsEveryNFrames == 0 && buffer.isNotEmpty()) {
                val text = recognizer.recognize(buffer.toShortArray(), frame.sampleRateHz, languageIso)
                return RecognizedUtterance(
                    text = text,
                    lang = languageIso,
                    isFinal = false,
                    confidence = 0f,
                    startTsMs = utteranceStartTsMs,
                    endTsMs = frame.captureTsNanos / 1_000_000
                )
            }
        }
        return null
    }
}
