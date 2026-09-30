package com.itantra.codec

import com.itantra.core.PacketPriority
import com.itantra.core.ReceivedUtterance
import com.itantra.core.RecognizedUtterance
import com.itantra.core.SemanticPacket

/**
 * Orchestrates §22's pipeline: consumes [RecognizedUtterance] from
 * Division A, produces [SemanticPacket] for Division D (outbound); and
 * the reverse on the receiving side.
 *
 * NEVER transmits raw embeddings (§22) — the wire representation is
 * always integer token IDs (+ optional residual bytes, currently always
 * null per [NoOpNeuralResidual]).
 *
 * This is the "production candidate" subword codec — measured, on a small
 * placeholder corpus, at roughly 40% of raw-UTF-8-baseline size after a
 * real bug fix to vocabulary ID assignment (frequency-order, not discovery
 * order; see [BpeTokenizerTrainer]'s KDoc and codec/validation/RESULTS.md
 * for the full before/after numbers, including one sentence that is STILL
 * larger than baseline — a tracked, unresolved edge case). It has NOT been
 * trained on a real per-language corpus (§21) and has NOT been benchmarked
 * on-device; treat "40% of baseline" as a promising placeholder-corpus
 * result, not a shipped number.
 *
 * INTEGRATION NOTE: renamed from `SemanticCodec` (its name in the Part IV
 * communication-stack archive) to avoid colliding with the `SemanticCodec`
 * *interface* in this module, which it now implements. Unlike the original,
 * `encode()` takes [priority] and [ttlMs] as explicit parameters (matching
 * [Utf8BaselineCodec]'s signature and the [SemanticCodec] interface)
 * rather than deciding them internally — callers wanting the original's
 * automatic alert-keyword-based priority selection should consult
 * [AlertLexicon] (now a standalone, codec-independent policy object)
 * before calling `encode()`, e.g.
 * `AlertLexicon.priorityFor(utterance.text, utterance.lang)`.
 */
class BpeSemanticCodec(
    private val tokenizer: BpeTokenizer,
    private val residualEncoder: NeuralResidualEncoder = NoOpNeuralResidual()
) : SemanticCodec {

    override val name: String = "bpe-subword-v0"

    override fun encode(
        utterance: RecognizedUtterance,
        priority: PacketPriority,
        ttlMs: Int,
        sentenceId: Long
    ): SemanticPacket =
        encode(
            utterance,
            priority,
            ttlMs,
            sentenceId,
            rawPcmForResidual = null
        )

    /** Overload exposing the optional residual-encoding path (§23), not part of the base [SemanticCodec] interface. */
    fun encode(
        utterance: RecognizedUtterance,
        priority: PacketPriority,
        ttlMs: Int,
        sentenceId: Long,
        rawPcmForResidual: ShortArray?
    ): SemanticPacket {
        require(utterance.isFinal) {
            "BpeSemanticCodec.encode should only be called on isFinal=true utterances " +
                    "(§19: only isFinal hypotheses are eligible for transmission)"
        }

        val tokenIds = tokenizer.encode(utterance.text)

        val residual: ByteArray? =
            if (FeatureFlags.neuralResidualEnabled && rawPcmForResidual != null) {
                residualEncoder.encode(rawPcmForResidual, tokenIds)
            } else {
                null
            }

        return SemanticPacket(
            tokenIds = tokenIds,
            lang = utterance.lang,
            priority = priority,
            residualPayload = residual,
            ttlMs = ttlMs,
            sentenceId = sentenceId
        )
    }

    override fun decode(packet: SemanticPacket): ReceivedUtterance {
        val text = tokenizer.decode(packet.tokenIds)

        val residualPayload = packet.residualPayload

        val hadResidual =
            residualPayload != null &&
                    FeatureFlags.neuralResidualEnabled &&
                    residualEncoder.decode(
                        residualPayload,
                        semanticTokenIds = packet.tokenIds
                    ) != null

        // §22: "an incomplete alert must fail loudly, not produce a fabricated message" — if
        // decode produced only <unk> tokens, this is exactly that failure mode, and the
        // confidence signal below must reflect it rather than hide it behind a normal-looking
        // ReceivedUtterance.
        val unkCount = packet.tokenIds.count { it == 0 } // vocab id 0 is always <unk>, see Tokenizer.kt

        val confidence = if (packet.tokenIds.isEmpty()) {
            0f
        } else {
            1f - (unkCount.toFloat() / packet.tokenIds.size)
        }

        return ReceivedUtterance(
            text = text,
            lang = packet.lang,
            confidence = confidence,
            reconstructedFromResidual = hadResidual
        )
    }
}