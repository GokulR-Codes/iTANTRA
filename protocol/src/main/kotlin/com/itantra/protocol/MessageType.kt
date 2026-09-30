package com.itantra.protocol

/**
 * Wire-level message type, §24 header table, "Message Type" field (1 byte).
 * Ordinal is NOT used on the wire — [code] is explicit and stable so that
 * reordering this enum in source never changes wire compatibility.
 */
enum class MessageType(val code: Int) {
    PARTIAL(0x01),
    FINAL(0x02),
    ALERT(0x03),
    RESIDUAL(0x04),
    ACK(0x05),
    PING(0x06),
    PAIR(0x07);

    companion object {
        fun fromCode(code: Int): MessageType =
            entries.firstOrNull { it.code == code }
                ?: throw PacketFormatException("Unknown MessageType code=$code")
    }
}
