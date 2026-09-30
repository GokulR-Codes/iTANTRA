package com.itantra.tts

import com.itantra.core.Language
import com.itantra.core.SynthesizedAudio

/**
 * TTS engine contract (§18, §20). One acoustic-model+vocoder pack per
 * language (§21); at most one resident at a time on Class L (§29). No
 * implementation exists yet — see §18's TTS candidate matrix
 * (AI4Bharat Indic-TTS, VITS-Rasa-13, sherpa-onnx bundled English).
 *
 * INTEGRATION NOTE (see docs/FILE_CONFLICT_MATRIX.md row "SynthesizedAudio"):
 * this module previously declared its own `SynthesizedAudio` data class with
 * a different field set (`durationMs`/`firstAudioMs`) than `core.Contracts`'
 * copy (`isAlert`/`firstAudioReadyTsMs`). The core copy is canonical (single
 * source of truth); its one genuinely new field, `durationMs`, was folded
 * into `core.SynthesizedAudio` as an additive field during the merge.
 */
interface TtsEngine {
    val activeLanguage: Language

    /** Synthesizes [text] to PCM. Implementations must report
     *  [SynthesizedAudio.firstAudioReadyTsMs] honestly, never a guess.
     *  Callers should normalize [text] with [TextFrontEnd] first. */
    fun synthesize(text: String, isAlert: Boolean): SynthesizedAudio

    fun loadLanguage(language: Language)
    fun unloadLanguage()
}

class UnimplementedTtsEngine(override val activeLanguage: Language) : TtsEngine {
    override fun synthesize(text: String, isAlert: Boolean): SynthesizedAudio {
        throw NotImplementedError(
            "No TTS model is wired in yet. See §18's TTS candidate " +
                "matrix; adoption requires a filled Model Scorecard, not this stub."
        )
    }

    override fun loadLanguage(language: Language) {
        throw NotImplementedError("UnimplementedTtsEngine cannot load a language — no model backs it.")
    }
    override fun unloadLanguage() { /* no-op */ }
}
