package com.itantra.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown by [Packet.decode] on any malformed, truncated, or corrupted input.
 * §30 requires fail-closed parsing: this is the ONLY way decode() reports a
 * problem — it never returns a partially-populated Packet, never guesses at
 * missing fields, and the parser contains no reflection- or eval-based
 * deserialization anywhere in this path.
 */
class PacketFormatException(message: String) : Exception(message)

/**
 * Bit flags packed into the single "Flags" byte, §24 header table.
 * Bits 4-7 are reserved — decode() must not reject an otherwise-valid
 * packet for having reserved bits set (forward compatibility), but must
 * never interpret them as anything either.
 */
object PacketFlags {
    const val FRAGMENTED = 0x01
    const val IS_FINAL = 0x02
    const val IS_ALERT = 0x04
    const val REQUIRES_ACK = 0x08
    const val RESERVED_MASK = 0xF0
}

/**
 * The wire packet, §24.
 *
 * Layout (network byte order / big-endian throughout):
 *
 * ```
 * offset  size  field
 * 0       1     magicVersion   (0xA<version-nibble>, currently 0xA1)
 * 1       1     flags          (see PacketFlags)
 * 2       4     sessionId
 * 6       4     senderShortId  (pairing-derived, NEVER a raw MAC address)
 * 10      2     messageId      (reassembly key)
 * 12      2     sequenceNumber (per-session monotonic)
 * 14      4     timestampMs    (sender-side monotonic ms, low 32 bits — see WARNING below)
 * 18      1     langCode
 * 19      1     messageType    (MessageType.code)
 * 20      1     fragmentIndex
 * 21      1     fragmentCount
 * 22      2     payloadLength
 * ---     24    HEADER TOTAL ---
 * 24      N     payload
 * 24+N    2     crc16 (over bytes [0, 24+N) — header+payload, NOT itself)
 * ```
 *
 * §24 resolves a real inconsistency in the supplied blueprint (its field
 * table summed to 26 bytes while its prose claimed a 24-byte header) by
 * treating the CRC as a 2-byte TRAILER appended after the payload, not
 * part of the fixed header. [HEADER_SIZE] below is the enforced proof of
 * that resolution — a unit test asserts it equals 24.
 *
 * WARNING — [timestampMs] wire truncation, found by executing the Python
 * reference mirror (protocol/validation/protocol_reference.py) against a
 * realistic Unix-epoch value: the wire field is only 4 bytes, i.e. an
 * unsigned 32-bit millisecond counter that wraps every ~49.7 days. Do
 * NOT populate this field with `System.currentTimeMillis()` (Unix epoch)
 * — it will silently truncate. Populate it with a SESSION-RELATIVE
 * monotonic value (e.g. `SystemClock.elapsedRealtime() - sessionStartMs`,
 * reset per pairing/session) so it never approaches the rollover boundary
 * within one conversation. §34's latency measurements must account for
 * this when correlating sender/receiver timestamps.
 */
data class Packet(
    val version: Int = CURRENT_VERSION,
    val flags: Int,
    val sessionId: Int,
    val senderShortId: Int,
    val messageId: Int,
    val sequenceNumber: Int,
    val timestampMs: Long,
    val langCode: Int,
    val messageType: MessageType,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val payload: ByteArray
) {
    val isFragmented: Boolean get() = flags and PacketFlags.FRAGMENTED != 0
    val isFinal: Boolean get() = flags and PacketFlags.IS_FINAL != 0
    val isAlert: Boolean get() = flags and PacketFlags.IS_ALERT != 0
    val requiresAck: Boolean get() = flags and PacketFlags.REQUIRES_ACK != 0

    init {
        require(version in 0..15) { "version must fit a 4-bit nibble, was $version" }
        require(messageId in 0..0xFFFF) { "messageId out of 2-byte range: $messageId" }
        require(sequenceNumber in 0..0xFFFF) { "sequenceNumber out of 2-byte range: $sequenceNumber" }
        require(langCode in 0..0xFF) { "langCode out of 1-byte range: $langCode" }
        require(fragmentIndex in 0..0xFF) { "fragmentIndex out of 1-byte range: $fragmentIndex" }
        require(fragmentCount in 0..0xFF) { "fragmentCount out of 1-byte range: $fragmentCount" }
        require(payload.size <= 0xFFFF) { "payload exceeds 2-byte length field: ${payload.size}" }
    }

    /** Serializes to header(24) + payload(N) + crc16 trailer(2). */
    fun encode(): ByteArray {
        val total = HEADER_SIZE + payload.size + TRAILER_SIZE
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)

        val magicVersion = (MAGIC_NIBBLE shl 4) or (version and 0x0F)
        buf.put(magicVersion.toByte())
        buf.put((flags and 0xFF).toByte())
        buf.putInt(sessionId)
        buf.putInt(senderShortId)
        buf.putShort(messageId.toShort())
        buf.putShort(sequenceNumber.toShort())
        buf.putInt((timestampMs and 0xFFFFFFFFL).toInt())
        buf.put(langCode.toByte())
        buf.put(messageType.code.toByte())
        buf.put(fragmentIndex.toByte())
        buf.put(fragmentCount.toByte())
        buf.putShort(payload.size.toShort())
        buf.put(payload)

        val crc = Crc16.compute(buf.array(), 0, HEADER_SIZE + payload.size)
        buf.putShort(crc.toShort())

        return buf.array()
    }

    companion object {
        const val MAGIC_NIBBLE = 0xA
        const val CURRENT_VERSION = 1
        const val HEADER_SIZE = 24
        const val TRAILER_SIZE = 2

        /**
         * Parses a wire packet. Fails closed: any truncation, magic
         * mismatch, unsupported version, length mismatch, or CRC mismatch
         * throws [PacketFormatException] rather than returning a guessed
         * or partial result. §24: "an incomplete alert must fail loudly,
         * not produce a fabricated message" — this function is the first
         * line of that guarantee.
         */
        fun decode(bytes: ByteArray): Packet {
            if (bytes.size < HEADER_SIZE + TRAILER_SIZE) {
                throw PacketFormatException(
                    "Truncated packet: ${bytes.size} bytes, need at least ${HEADER_SIZE + TRAILER_SIZE}"
                )
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val magicVersionByte = buf.get().toInt() and 0xFF
            val magic = (magicVersionByte shr 4) and 0x0F
            val version = magicVersionByte and 0x0F
            if (magic != MAGIC_NIBBLE) {
                throw PacketFormatException("Bad magic nibble: expected $MAGIC_NIBBLE, got $magic")
            }
            if (version != CURRENT_VERSION) {
                // Fail closed on unknown versions rather than guessing at a
                // layout we don't know. Revisit if/when a v2 is introduced
                // with a documented migration path in DECISIONS.md.
                throw PacketFormatException("Unsupported version: $version")
            }

            val flags = buf.get().toInt() and 0xFF
            val sessionId = buf.int
            val senderShortId = buf.int
            val messageId = buf.short.toInt() and 0xFFFF
            val sequenceNumber = buf.short.toInt() and 0xFFFF
            val timestampMs = buf.int.toLong() and 0xFFFFFFFFL
            val langCode = buf.get().toInt() and 0xFF
            val messageTypeCode = buf.get().toInt() and 0xFF
            val fragmentIndex = buf.get().toInt() and 0xFF
            val fragmentCount = buf.get().toInt() and 0xFF
            val payloadLength = buf.short.toInt() and 0xFFFF

            val expectedTotal = HEADER_SIZE + payloadLength + TRAILER_SIZE
            if (bytes.size != expectedTotal) {
                throw PacketFormatException(
                    "Length mismatch: payloadLength=$payloadLength implies total=$expectedTotal, " +
                        "actual=${bytes.size}"
                )
            }

            val payload = ByteArray(payloadLength)
            buf.get(payload)

            val crcReceived = buf.short.toInt() and 0xFFFF
            val crcComputed = Crc16.compute(bytes, 0, HEADER_SIZE + payloadLength)
            if (crcReceived != crcComputed) {
                throw PacketFormatException(
                    "CRC mismatch: received=0x${crcReceived.toString(16)} " +
                        "computed=0x${crcComputed.toString(16)}"
                )
            }

            val messageType = MessageType.fromCode(messageTypeCode)

            return Packet(
                version = version,
                flags = flags,
                sessionId = sessionId,
                senderShortId = senderShortId,
                messageId = messageId,
                sequenceNumber = sequenceNumber,
                timestampMs = timestampMs,
                langCode = langCode,
                messageType = messageType,
                fragmentIndex = fragmentIndex,
                fragmentCount = fragmentCount,
                payload = payload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return version == other.version && flags == other.flags && sessionId == other.sessionId &&
            senderShortId == other.senderShortId && messageId == other.messageId &&
            sequenceNumber == other.sequenceNumber && timestampMs == other.timestampMs &&
            langCode == other.langCode && messageType == other.messageType &&
            fragmentIndex == other.fragmentIndex && fragmentCount == other.fragmentCount &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + flags
        result = 31 * result + sessionId
        result = 31 * result + senderShortId
        result = 31 * result + messageId
        result = 31 * result + sequenceNumber
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + langCode
        result = 31 * result + messageType.hashCode()
        result = 31 * result + fragmentIndex
        result = 31 * result + fragmentCount
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
