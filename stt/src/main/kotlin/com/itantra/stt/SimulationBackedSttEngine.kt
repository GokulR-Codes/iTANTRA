package com.itantra.stt

import com.itantra.core.AudioFrame
import com.itantra.core.Language
import com.itantra.core.RecognizedUtterance
import com.itantra.vad.EndpointerConfig
import com.itantra.vad.EnergyGateVad
import com.itantra.vad.Endpointer

/**
 * A recognizer that produces NO transcript text — it returns an explicit marker string
 * rather than inventing words, because fabricating recognized text (even as a "demo")
 * is exactly what the master integration prompt's "do not fake success" rule prohibits.
 * It exists only so [SimulatedStreamingSttAdapter]'s buffering/endpointing logic — which
 * IS real and tested — can be exercised end-to-end in wiring tests and manual demos
 * before a real backend (§18 candidate matrix) is integrated.
 */
class SilentPlaceholderRecognizer : OfflineRecognizer {
    override fun recognize(pcm16: ShortArray, sampleRateHz: Int, languageIso: String): String =
        "[simulated-stt: ${pcm16.size} samples @ ${sampleRateHz}Hz, lang=$languageIso — no real model wired]"
}

/**
 * Adapts Part III's validated [SimulatedStreamingSttAdapter] (buffering + endpointing
 * around an [OfflineRecognizer]) onto the canonical [SttEngine] interface, so it can be
 * plugged into anything written against [SttEngine] — the android-app session wiring,
 * benchmark harness, etc. — for local pipeline demos.
 *
 * *** THIS IS NOT REAL SPEECH RECOGNITION. *** By default it is constructed with
 * [SilentPlaceholderRecognizer], which never produces real words. Do not swap in a
 * recognizer that fabricates plausible-looking transcripts "for the demo" — pass a real
 * offline model (once one is integrated per §18) instead, or leave this class unused
 * outside of wiring/integration tests. Any status report that cites this class as
 * evidence that "STT works" is misusing it.
 */
class SimulationBackedSttEngine(
    override val activeLanguage: Language,
    recognizer: OfflineRecognizer = SilentPlaceholderRecognizer(),
    endpointerConfig: EndpointerConfig = EndpointerConfig()
) : SttEngine {

    private var loadedLanguage: Language? = activeLanguage
    private val adapter = SimulatedStreamingSttAdapter(
        recognizer = recognizer,
        endpointer = Endpointer(endpointerConfig),
        languageIso = activeLanguage.code
    )
    private val vad = EnergyGateVad()

    override fun onFrame(frame: AudioFrame): RecognizedUtterance? {
        check(loadedLanguage != null) { "SimulationBackedSttEngine.onFrame called after unloadLanguage()" }
        val vadResult = vad.processFrame(frame)
        return adapter.onFrame(frame, vadResult.isSpeech)
    }

    override fun lastRtf(): Double? = null // simulation performs no real inference; RTF is not meaningful

    override fun loadLanguage(language: Language) {
        loadedLanguage = language
    }

    override fun unloadLanguage() {
        loadedLanguage = null
    }
}
