package com.itantra.tts

import java.text.Normalizer

/**
 * §20: "Shared text front-end (Unicode/script normalization, common across
 * languages where scripts allow) feeding per-language acoustic model +
 * vocoder pairs."
 *
 * This does generic Unicode NFC normalization plus whitespace cleanup that
 * is safe across Indic scripts and Latin script alike. It intentionally
 * does NOT do language-specific number/date expansion or transliteration —
 * those are per-language concerns to add once a specific TTS backend's
 * expected input format is confirmed (§18's "must verify before adoption"
 * column). Tracked [OPEN] in docs/TODO.md.
 */
object TextFrontEnd {
    private val multiWhitespace = Regex("\\s+")

    fun normalize(rawText: String): String {
        val nfc = Normalizer.normalize(rawText, Normalizer.Form.NFC)
        val trimmed = nfc.trim()
        return multiWhitespace.replace(trimmed, " ")
    }
}
