package com.itantra.core.registry

import com.itantra.core.Language

/**
 * §21/§29: at most one language pack may be "resident" at a time under the
 * default (Class L) load policy — "never keep more than the active
 * language's models resident." §29 additionally flags this as a
 * Class-L-breaking bug, not a style choice, if violated. [loadLanguage] is
 * the single choke point that enforces this; Division C's resource
 * orchestrator should route all pack loads through this class rather than
 * loading STT/TTS models directly.
 */
class LanguageRegistry(
    private val manifests: MutableMap<Language, LanguagePackManifest> = mutableMapOf()
) {
    var residentLanguage: Language? = null
        private set

    private val onUnload = mutableListOf<(Language) -> Unit>()
    private val onLoad = mutableListOf<(Language) -> Unit>()

    fun register(manifest: LanguagePackManifest) {
        manifests[manifest.language] = manifest
    }

    fun manifestFor(language: Language): LanguagePackManifest? = manifests[language]

    fun isSupported(language: Language): Boolean =
        manifests[language]?.validationStatus == ValidationStatus.VALIDATED

    /**
     * §21 failure condition (Division A, §12): "a language pack that has not
     * passed its own validation record must never silently substitute
     * another language's model." This throws rather than falling back.
     */
    fun requireSupported(language: Language): LanguagePackManifest {
        val manifest = manifests[language]
            ?: error("No manifest registered for $language — refusing to substitute another language's model.")
        if (manifest.validationStatus != ValidationStatus.VALIDATED) {
            error(
                "$language is not VALIDATED (status=${manifest.validationStatus}) — " +
                    "refusing to silently substitute another language's model (§21 failure condition)."
            )
        }
        return manifest
    }

    /** Loads [language], unloading whatever was previously resident first. */
    fun loadLanguage(language: Language) {
        val current = residentLanguage
        if (current == language) return
        if (current != null) onUnload.forEach { it(current) }
        residentLanguage = language
        onLoad.forEach { it(language) }
    }

    fun addUnloadListener(listener: (Language) -> Unit) {
        onUnload += listener
    }

    fun addLoadListener(listener: (Language) -> Unit) {
        onLoad += listener
    }

    fun allManifests(): List<LanguagePackManifest> = manifests.values.toList()
}
