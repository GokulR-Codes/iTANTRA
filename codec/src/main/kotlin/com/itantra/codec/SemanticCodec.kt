package com.itantra.codec

import com.itantra.core.PacketPriority
import com.itantra.core.ReceivedUtterance
import com.itantra.core.RecognizedUtterance
import com.itantra.core.SemanticPacket

/**
 * Encodes a [RecognizedUtterance] into transmittable bytes and decodes it back.
 * Every implementation must be measured against [Utf8BaselineCodec] before it
 * is allowed to claim "low-bitrate" (§35, §46 Definition of Done) — the claim
 * requires a stated baseline, not just "smaller than sending audio would be."
 *
 * INTEGRATION NOTE (see docs/FILE_CONFLICT_MATRIX.md row "SemanticCodec"):
 * `decode()` returns [ReceivedUtterance], not a bare [String] — the Gate1 git
 * archive's original interface returned a bare String, which loses the
 * confidence/reconstructed-from-residual information the Part IV
 * communication stack's real implementation ([BpeSemanticCodec]) already
 * computes and which Division A's TTS stage genuinely needs downstream. That
 * class name collided with this interface's name in Part IV (both were
 * called `SemanticCodec` in `itantra.codec` — one an interface, one a
 * concrete class); it has been renamed `BpeSemanticCodec` here.
 */
interface SemanticCodec {
    fun encode(utterance: RecognizedUtterance, priority: PacketPriority, ttlMs: Int, sentenceId: Long): SemanticPacket
    fun decode(packet: SemanticPacket): ReceivedUtterance
    /** Human-readable name used in BENCHMARKS.md bitrate tables (§35, §40). */
    val name: String
}

/**
 * The mandatory §35 baseline: raw UTF-8 text, packed one byte-value per
 * "token" so it fits the same [SemanticPacket.tokenIds] shape as a real
 * tokenizer would. This is deliberately the *dumbest possible* representation
 * — every future codec (BPE/unigram subwords, entropy-coded, etc.) exists to
 * beat this number, and that comparison must be run and recorded, not assumed
 * (§22, §35: "do not assume the semantic representation wins — prove it").
 * See [BpeSemanticCodec] for the measured, real subword alternative and
 * codec/validation/RESULTS.md for the actual comparison against this class.
 */
class Utf8BaselineCodec : SemanticCodec {
    override val name: String = "utf8-baseline"

    override fun encode(
        utterance: RecognizedUtterance,
        priority: PacketPriority,
        ttlMs: Int,
        sentenceId: Long
    ): SemanticPacket {
        val bytes = utterance.text.toByteArray(Charsets.UTF_8)
        val tokenIds = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        return SemanticPacket(
            tokenIds = tokenIds,
            lang = utterance.lang,
            priority = priority,
            residualPayload = null,
            ttlMs = ttlMs,
            sentenceId = sentenceId
        )
    }

    override fun decode(packet: SemanticPacket): ReceivedUtterance {
        val bytes = ByteArray(packet.tokenIds.size) { packet.tokenIds[it].toByte() }
        val text = String(bytes, Charsets.UTF_8)
        // UTF-8 baseline never loses information if the packet arrived at all (no lossy
        // tokenization step), so confidence is 1.0 and there is no residual reconstruction.
        return ReceivedUtterance(text = text, lang = packet.lang, confidence = 1f, reconstructedFromResidual = false)
    }

    /** Bits/second for a given payload size and utterance duration, per §16/§35's formula. */
    fun payloadBitsPerSecond(payloadBytes: Int, durationSeconds: Double): Double {
        require(durationSeconds > 0) { "durationSeconds must be positive" }
        return 8.0 * payloadBytes / durationSeconds
    }
}
