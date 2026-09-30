package com.itantra.core.registry

import com.itantra.core.Language

enum class ValidationStatus { NOT_STARTED, IN_PROGRESS, VALIDATED, FAILED }

/**
 * §40 "Model manifest" fields, one instance per STT or TTS model.
 * sha256Checksum/conversionCommand/quantization/benchmarkReferencePath are
 * nullable on purpose: they are unknown until a real checkpoint has actually
 * been pulled, converted and benchmarked. A non-null placeholder here would
 * be exactly the kind of fabricated-looking evidence Prime Directive 2
 * prohibits.
 */
data class ModelManifest(
    val name: String,
    val version: String,
    val sourceUrl: String,
    val license: String,
    val sha256Checksum: String? = null,
    val conversionCommand: String? = null,
    val quantization: String? = null,
    val benchmarkReferencePath: String? = null
)

/**
 * §21: one manifest per language, tying its STT + TTS models and validation
 * record together, so the codebase does not become "ten duplicated apps."
 * wer/cer/alertKeywordRecall are null until actually [MEASURED] (§33) —
 * never fill these with placeholder numbers.
 */
data class LanguagePackManifest(
    val language: Language,
    val sttModel: ModelManifest,
    val ttsModel: ModelManifest,
    val validationStatus: ValidationStatus = ValidationStatus.NOT_STARTED,
    val validationFocus: String,
    val wer: Double? = null,
    val cer: Double? = null,
    val alertKeywordRecall: Double? = null
)
