package com.itantra.codec

/**
 * Small seed alert lexicon, one entry per required language (§5's 9
 * Indic + English), sourced from the same placeholder phrases used in
 * codec/validation/corpus/alert_sentences.tsv. THIS IS A STARTING POINT,
 * NOT A VALIDATED LEXICON — §19 calls for the STT-side domain lexicon to
 * be tuned against a real noise-robustness/keyword-recall benchmark
 * (§33); this codec-side copy exists so §22's "preserve alert words...
 * with explicit priority markers" has something concrete to check
 * against today. Expand and native-speaker-review before Gate 9.
 *
 * Separated out from [BpeSemanticCodec] during the iTantra-Master merge
 * so priority *policy* (what counts as an alert) is reusable independently
 * of which codec implementation is doing the encoding — see
 * docs/FILE_CONFLICT_MATRIX.md row "SemanticCodec".
 */
object AlertLexicon {
    // lang code -> set of alert-relevant words (as they appear in RecognizedUtterance.text)
    private val words: Map<String, Set<String>> = mapOf(
        "hi" to setOf("सहायता", "आग", "बाहर", "निकलो", "पानी"),
        "gu" to setOf("મદદ", "આગ", "બહાર", "નીકળો", "પાણી"),
        "mr" to setOf("मदत", "आग", "बाहेर", "पडा", "पाणी"),
        "kn" to setOf("ಸಹಾಯ", "ಬೆಂಕಿ", "ಹೊರಗೆ", "ನೀರು"),
        "ml" to setOf("സഹായം", "തീ", "പുറത്തിറങ്ങുക", "വെള്ളം"),
        "ta" to setOf("உதவி", "தீ", "வெளியேறுங்கள்", "தண்ணீர்"),
        "te" to setOf("సహాయం", "మంట", "బయటకు", "నీరు"),
        "or" to setOf("ସାହାଯ୍ୟ", "ନିଆଁ", "ବାହାରକୁ", "ପାଣି"),
        "bn" to setOf("সাহায্য", "আগুন", "বাইরে", "জল"),
        "en" to setOf("help", "fire", "evacuate", "flood", "medical", "emergency")
    )

    fun containsAlertWord(text: String, lang: String): Boolean {
        val bucket = words[lang] ?: return false
        val tokens = text.split(Regex("\\s+"))
        return tokens.any { it in bucket }
    }

    /** Convenience policy helper: ALERT_BASE if the text contains a known alert word for [lang], else NORMAL_BASE. */
    fun priorityFor(text: String, lang: String): com.itantra.core.PacketPriority =
        if (containsAlertWord(text, lang)) com.itantra.core.PacketPriority.ALERT_BASE
        else com.itantra.core.PacketPriority.NORMAL_BASE
}
