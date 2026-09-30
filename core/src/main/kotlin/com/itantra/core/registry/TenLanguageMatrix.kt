package com.itantra.core.registry

import com.itantra.core.Language

/**
 * §21's ten-language production matrix, seeded as data instead of a doc
 * table so LanguageRegistry has something real to register against.
 *
 * Every candidate name/license here is [ASSUMPTION], carried over from
 * §18's candidate matrix — NOT re-verified per-checkpoint. §9 requires
 * re-checking license text on the *exact* checkpoint pulled, which has not
 * happened in this session. sha256Checksum is deliberately null on every
 * entry: nothing has actually been downloaded or hashed here.
 *
 * Do not treat this table as evidence of "supported" per §46 — it is a
 * to-do list with ten rows, all currently NOT_STARTED.
 */
object TenLanguageMatrix {

    private fun indicConformer() = ModelManifest(
        name = "AI4Bharat IndicConformer (600M multilingual)",
        version = "unpinned",
        sourceUrl = "https://github.com/AI4Bharat/IndicConformerASR",
        license = "MIT per AIKosh listing — [ASSUMPTION], re-verify on the exact checkpoint (§9)"
    )

    private fun indicTts() = ModelManifest(
        name = "AI4Bharat Indic-TTS (FastPitch + HiFi-GAN V1)",
        version = "unpinned",
        sourceUrl = "https://github.com/AI4Bharat/Indic-TTS",
        license = "[OPEN] — verify per-checkpoint license text before use (§9, §18)"
    )

    private fun sherpaOnnxEnglishTts() = ModelManifest(
        name = "sherpa-onnx bundled English TTS (Piper/VITS-class)",
        version = "unpinned",
        sourceUrl = "https://github.com/k2-fsa/sherpa-onnx",
        license = "Apache-2.0 for the runtime; verify the specific bundled voice model's license separately"
    )

    val entries: List<LanguagePackManifest> = listOf(
        LanguagePackManifest(Language.HI, indicConformer(), indicTts(), validationFocus = "Devanagari, accents, noise"),
        LanguagePackManifest(Language.GU, indicConformer(), indicTts(), validationFocus = "Script + numerals"),
        LanguagePackManifest(Language.MR, indicConformer(), indicTts(), validationFocus = "Devanagari disambiguation"),
        LanguagePackManifest(Language.KN, indicConformer(), indicTts(), validationFocus = "Morphology + pronunciation"),
        LanguagePackManifest(Language.ML, indicConformer(), indicTts(), validationFocus = "Long compound words"),
        LanguagePackManifest(Language.TA, indicConformer(), indicTts(), validationFocus = "Spoken/standard variation"),
        LanguagePackManifest(Language.TE, indicConformer(), indicTts(), validationFocus = "Sandhi + numerals"),
        LanguagePackManifest(Language.OR, indicConformer(), indicTts(), validationFocus = "Script + named entities"),
        LanguagePackManifest(Language.BN, indicConformer(), indicTts(), validationFocus = "Phonology + script"),
        LanguagePackManifest(
            Language.EN, indicConformer(), sherpaOnnxEnglishTts(),
            validationFocus = "Indian-accent English + code-switch"
        )
    )

    fun seedRegistry(registry: LanguageRegistry) {
        entries.forEach { registry.register(it) }
    }
}
