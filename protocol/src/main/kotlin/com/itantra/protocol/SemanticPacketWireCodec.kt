package com.itantra.protocol

import com.itantra.core.Language
import com.itantra.core.PacketPriority
import com.itantra.core.SemanticPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `SemanticPacket` <-> wire-bytes serialization for [Packet.payload].
 *
 * FOUND THIS SESSION, NOT PORTED FROM ANYWHERE: grepping the whole repository turned up no
 * code anywhere that turns a `codec.SemanticCodec`-produced [SemanticPacket] into bytes a
 * [Packet]/transport can actually carry, or back. `android-app/SessionService.kt` is an
 * empty shell (`onBind` returning null, nothing else) — the wiring simply didn't exist yet.
 * This object is that missing piece, used by `:orchestrator`.
 *
 * Design choices, stated plainly:
 *  - [SemanticPacket.lang] (a string like `"en"`) is carried on the wire as [Packet.langCode]
 *    (1 byte) via [Language]'s ordinal, not re-encoded again inside the payload — no reason
 *    to spend payload bytes on it twice. [langCodeFor]/[langFor] are the two directions of
 *    that mapping; an unmapped language returns/accepts [UNKNOWN_LANG_CODE] rather than
 *    guessing.
 *  - [SemanticPacket.priority] does **not** round-trip on the wire at all. It is a sender-
 *    local *queueing* decision (which `RetransmissionQueue` tier to use), not information the
 *    receiver needs to reconstruct the message — the receiver only recovers the coarse
 *    "was this flagged alert" bit via [Packet.isAlert] (already an existing wire flag).
 *    [decodePayload] takes the priority to use for the reconstructed [SemanticPacket] as a
 *    parameter for exactly this reason: the caller (typically `:orchestrator`, deriving it
 *    from `Packet.isAlert`) decides it, this object doesn't invent one.
 *  - Every integer field is a fixed-width big-endian value (matching [Packet]'s own
 *    encoding convention), not a compact/varint encoding — this prioritizes obvious
 *    correctness over payload size. If a smaller encoding is ever wanted, that is a
 *    `codec`-module-level "beat the baseline, prove it" question (see
 *    `codec/validation/RESULTS.md`), not something to bury silently in the wire codec.
 */
object SemanticPacketWireCodec {

    const val UNKNOWN_LANG_CODE = 0xFF

    fun langCodeFor(lang: String): Int = Language.fromCode(lang)?.ordinal ?: UNKNOWN_LANG_CODE

    fun langFor(langCode: Int): String = Language.entries.getOrNull(langCode)?.code ?: "und"

    /** Layout: sentenceId(8) + ttlMs(4) + tokenCount(4) + tokenIds(4 each) + residualLength(4) + residual(N). */
    fun encodePayload(packet: SemanticPacket): ByteArray {
        val residual = packet.residualPayload ?: ByteArray(0)
        val size = 8 + 4 + 4 + packet.tokenIds.size * 4 + 4 + residual.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(packet.sentenceId)
        buf.putInt(packet.ttlMs)
        buf.putInt(packet.tokenIds.size)
        for (id in packet.tokenIds) buf.putInt(id)
        buf.putInt(residual.size)
        buf.put(residual)
        return buf.array()
    }

    /**
     * Fails closed (throws [PacketFormatException]) on truncated or internally inconsistent
     * input, matching [Packet.decode]'s own "never return a partially-populated / guessed
     * result" discipline — a corrupt payload must not silently become a shorter, wrong
     * message.
     */
    fun decodePayload(bytes: ByteArray, lang: String, priority: PacketPriority): SemanticPacket {
        if (bytes.size < 16) {
            throw PacketFormatException("truncated SemanticPacket payload: ${bytes.size} bytes, need at least 16")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val sentenceId = buf.long
        val ttlMs = buf.int
        val tokenCount = buf.int
        if (tokenCount < 0 || bytes.size < 16 + tokenCount * 4) {
            throw PacketFormatException("truncated/corrupt token section: tokenCount=$tokenCount, bytes=${bytes.size}")
        }
        val tokenIds = IntArray(tokenCount) { buf.int }
        if (buf.remaining() < 4) {
            throw PacketFormatException("truncated payload: missing residual-length field")
        }
        val residualLength = buf.int
        if (residualLength < 0 || buf.remaining() < residualLength) {
            throw PacketFormatException("truncated residual section: residualLength=$residualLength, remaining=${buf.remaining()}")
        }
        val residual = if (residualLength > 0) ByteArray(residualLength).also { buf.get(it) } else null

        return try {
            SemanticPacket(
                tokenIds = tokenIds,
                lang = lang,
                priority = priority,
                residualPayload = residual,
                ttlMs = ttlMs,
                sentenceId = sentenceId
            )
        } catch (e: IllegalArgumentException) {
            // SemanticPacket's own init{} (e.g. ttlMs > 0) rejected a structurally-parseable
            // but semantically invalid payload — still a decode failure, not a crash.
            throw PacketFormatException("decoded SemanticPacket failed validation: ${e.message}")
        }
    }
}
