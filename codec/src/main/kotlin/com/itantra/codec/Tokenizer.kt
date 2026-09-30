package com.itantra.codec

import java.text.Normalizer

/**
 * Language-aware BPE subword tokenizer, §22.
 *
 * Design mirrors codec/validation/bitrate_benchmark.py, which was
 * EXECUTED in this session (no kotlinc/gradle available — see
 * KNOWN_ISSUES.md) and is the source of truth for the algorithm's
 * correctness, including a real bug found and fixed during that
 * validation run: see [buildVocabulary]'s doc comment below before
 * changing ID assignment.
 *
 * Pipeline: Unicode NFC normalization -> whitespace word
 * pre-tokenization -> greedy application of learned BPE merges per word
 * -> integer token IDs. Training learns merges from a corpus; production
 * language packs ship a pre-trained [TokenizerModel] per §21's language
 * registry rather than training on-device.
 */

private const val END_MARKER = '\u0000'

data class TokenizerModel(
    val merges: List<Pair<String, String>>,
    val vocab: Map<String, Int>,
    val unkId: Int
)

class BpeTokenizerTrainer {
    /**
     * Trains merges + an initial vocabulary from [sentences]. Mirrors
     * bitrate_benchmark.py's `BpeTokenizer.train`. A pair is only merged
     * if it occurs at least twice — merging a singleton pair adds a
     * vocabulary entry for zero repeat benefit, wasted ID space on a
     * Class L device where every loaded language pack counts against the
     * memory budget (§29).
     */
    fun train(sentences: List<String>, numMerges: Int = 80): TokenizerModel {
        val normalized = sentences.map { Normalizer.normalize(it, Normalizer.Form.NFC) }
        var wordFreqs = mutableMapOf<List<String>, Int>()
        for (s in normalized) {
            for (w in s.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val symbols = w.map { it.toString() } + END_MARKER.toString()
                wordFreqs[symbols] = (wordFreqs[symbols] ?: 0) + 1
            }
        }

        val baseSymbols = sortedSetOf<String>()
        wordFreqs.keys.forEach { baseSymbols.addAll(it) }
        val vocabSymbols = mutableListOf<String>().apply { addAll(baseSymbols) }

        val merges = mutableListOf<Pair<String, String>>()
        repeat(numMerges) {
            val pairCounts = mutableMapOf<Pair<String, String>, Int>()
            for ((word, freq) in wordFreqs) {
                for (i in 0 until word.size - 1) {
                    val pair = word[i] to word[i + 1]
                    pairCounts[pair] = (pairCounts[pair] ?: 0) + freq
                }
            }
            val best = pairCounts.maxByOrNull { it.value } ?: return@repeat
            if (best.value < 2) return@repeat

            val (a, b) = best.key
            val mergedSymbol = a + b
            merges.add(best.key)
            vocabSymbols.add(mergedSymbol)

            val newWordFreqs = mutableMapOf<List<String>, Int>()
            for ((word, freq) in wordFreqs) {
                val out = mutableListOf<String>()
                var i = 0
                while (i < word.size) {
                    if (i < word.size - 1 && word[i] == a && word[i + 1] == b) {
                        out.add(mergedSymbol)
                        i += 2
                    } else {
                        out.add(word[i])
                        i += 1
                    }
                }
                newWordFreqs[out] = (newWordFreqs[out] ?: 0) + freq
            }
            wordFreqs = newWordFreqs
        }

        val vocab = buildVocabularyByFrequency(wordFreqs, vocabSymbols)
        return TokenizerModel(merges = merges, vocab = vocab, unkId = vocab.getValue("<unk>"))
    }

    /**
     * Assigns integer IDs by symbol FREQUENCY (most frequent -> lowest
     * ID), not by discovery order.
     *
     * WHY THIS MATTERS (found + fixed in codec/validation/
     * bitrate_benchmark.py, this session): the first version of this
     * validation assigned IDs in "base symbols in codepoint order, then
     * merges in discovery order." On a corpus dominated by Indic-script
     * sentences, English-specific merged symbols were discovered late,
     * landed at IDs >=128, and cost 2 bytes in LEB128 varint instead of
     * 1 — making 4 of 5 English test sentences LARGER than raw UTF-8.
     * Frequency-sorted assignment fixed the aggregate result (varint
     * size dropped from 48.1% to 39.9% of raw UTF-8 on the same
     * corpus) without a language-specific special case. See
     * codec/validation/RESULTS.md for the full measured comparison,
     * including one sentence ("evacuate now") that is STILL slightly
     * worse than raw UTF-8 even after this fix — a real, tracked,
     * unresolved edge case, not swept under the rug.
     */
    private fun buildVocabularyByFrequency(
        finalWordFreqs: Map<List<String>, Int>,
        allSymbolsEverSeen: List<String>
    ): Map<String, Int> {
        val symbolFreq = mutableMapOf<String, Int>()
        for ((word, freq) in finalWordFreqs) {
            for (sym in word) symbolFreq[sym] = (symbolFreq[sym] ?: 0) + freq
        }
        val ordered = symbolFreq.keys.sortedWith(
            compareByDescending<String> { symbolFreq.getValue(it) }.thenBy { it }
        )
        val vocab = linkedMapOf("<unk>" to 0)
        for (sym in ordered) vocab.putIfAbsent(sym, vocab.size)
        // Fail-safe: any symbol that appeared during training but not in
        // the final word-frequency map (shouldn't happen) still gets an
        // ID rather than being silently dropped.
        for (sym in allSymbolsEverSeen) vocab.putIfAbsent(sym, vocab.size)
        return vocab
    }
}

class BpeTokenizer(private val model: TokenizerModel) {

    fun encode(sentence: String): IntArray {
        val normalized = Normalizer.normalize(sentence, Normalizer.Form.NFC)
        val ids = mutableListOf<Int>()
        for (w in normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var symbols = (w.map { it.toString() } + END_MARKER.toString())
            for ((a, b) in model.merges) {
                val out = mutableListOf<String>()
                var i = 0
                while (i < symbols.size) {
                    if (i < symbols.size - 1 && symbols[i] == a && symbols[i + 1] == b) {
                        out.add(a + b)
                        i += 2
                    } else {
                        out.add(symbols[i])
                        i += 1
                    }
                }
                symbols = out
            }
            for (sym in symbols) ids.add(model.vocab[sym] ?: model.unkId)
        }
        return ids.toIntArray()
    }

    fun decode(ids: IntArray): String {
        val reverse = model.vocab.entries.associate { (k, v) -> v to k }
        val sb = StringBuilder()
        for (id in ids) sb.append(reverse[id] ?: "<unk>")
        return sb.toString().replace(END_MARKER.toString(), " ").trim()
    }
}
