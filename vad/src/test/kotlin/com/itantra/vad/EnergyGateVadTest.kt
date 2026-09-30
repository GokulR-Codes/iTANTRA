package com.itantra.vad

import com.itantra.core.AudioFrame
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * JUnit5 port of Part III's hand-rolled EnergyGateVad checks (all 4 passed under the
 * original kotlinc runner). See docs/DEVELOPMENT_HANDOFF.md for provenance.
 */
class EnergyGateVadTest {

    private fun frame(pcm: ShortArray, tsMs: Long = 0L): AudioFrame =
        AudioFrame(pcm16 = pcm, sampleRateHz = 16_000, channels = 1, captureTsNanos = tsMs * 1_000_000)

    private fun silenceFrame(nSamples: Int = 480): ShortArray = ShortArray(nSamples) { 0 }

    private fun toneFrame(nSamples: Int = 480, amplitude: Int = 12_000, freqHz: Double = 440.0): ShortArray =
        ShortArray(nSamples) { i -> (amplitude * sin(2 * PI * freqHz * i / 16_000.0)).toInt().toShort() }

    @Test
    fun `settles as non-speech on pure silence`() {
        val vad = EnergyGateVad()
        var last = vad.processFrame(frame(silenceFrame()))
        repeat(20) { last = vad.processFrame(frame(silenceFrame())) }
        assertFalse(last.isSpeech)
    }

    @Test
    fun `flags a loud tone as speech after a quiet noise floor, with higher probability than silence`() {
        val vad = EnergyGateVad()
        var lastSilence = vad.processFrame(frame(silenceFrame()))
        repeat(20) { lastSilence = vad.processFrame(frame(silenceFrame())) }

        val speechResult = vad.processFrame(frame(toneFrame()))
        assertTrue(speechResult.isSpeech)
        assertTrue(speechResult.speechProbability > lastSilence.speechProbability)
    }

    @Test
    fun `reset returns to initial non-speech behavior on silence`() {
        val vad = EnergyGateVad()
        repeat(20) { vad.processFrame(frame(toneFrame())) } // drive it away from initial state
        vad.reset()
        val afterReset = vad.processFrame(frame(silenceFrame()))
        assertFalse(afterReset.isSpeech)
    }
}
