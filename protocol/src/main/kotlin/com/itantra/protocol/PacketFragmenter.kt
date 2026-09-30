package com.itantra.protocol

/**
 * Transport-agnostic send-side fragmentation — the send-side counterpart to
 * [FragmentReassembler].
 *
 * FOUND AND FIXED THIS SESSION: before this file existed, the exact same "chunk a payload
 * into `mtu - HEADER_SIZE - TRAILER_SIZE`-sized pieces, handling the empty-payload case as
 * one empty fragment" logic was written twice — inline in `wifi.WifiUdpTransport.send()`
 * (which also built the [Packet] objects) and separately, only half the job, in
 * `bluetooth.BleFragmenter.fragment()` (raw chunking only; nothing built the [Packet]s for
 * BLE at all yet). This object is the single implementation both should use, and the one
 * new code (the `:orchestrator` module) uses directly. `WifiUdpTransport.send()` was
 * refactored this session to call [buildFragmentPackets] instead of repeating the logic —
 * see its updated KDoc for the compile-verification caveat that edit carries.
 */
object PacketFragmenter {

    /** [mtu] minus this format's fixed header+trailer overhead, minus any transport-specific
     *  [extraOverhead] (e.g. BLE's 3-byte ATT header) that eats into the same MTU budget. */
    fun maxFragmentPayload(mtu: Int, extraOverhead: Int = 0): Int =
        mtu - extraOverhead - Packet.HEADER_SIZE - Packet.TRAILER_SIZE

    /** Raw chunking only, no [Packet] framing — mirrors the previous
     *  `bluetooth.BleFragmenter.fragment()` behavior exactly (same empty-payload case). */
    fun chunkPayload(payload: ByteArray, maxFragmentPayload: Int): List<ByteArray> {
        require(maxFragmentPayload > 0) { "maxFragmentPayload must be positive, got $maxFragmentPayload" }
        return if (payload.isEmpty()) {
            listOf(ByteArray(0))
        } else {
            payload.toList().chunked(maxFragmentPayload).map { it.toByteArray() }
        }
    }

    /** [packets] in send order; [nextSequenceNumber] is the wrapped (`and 0xFFFF`) sequence
     *  number the caller should use for its next transmission (ACK, next message, ...). */
    data class FragmentedMessage(val messageId: Int, val packets: List<Packet>, val nextSequenceNumber: Int)

    /**
     * Chunks [payload] and builds one framed [Packet] per fragment — [PacketFlags.FRAGMENTED]
     * set on every fragment when there is more than one, [PacketFlags.IS_FINAL] on the last,
     * [PacketFlags.IS_ALERT] on all of them when [isAlert] is true. Identical behavior to the
     * fragmentation scheme `wifi_loopback_reference.py` proved against a real kernel UDP
     * socket (see `protocol/validation/RESULTS.md`), now available to any transport, not just
     * Wi-Fi.
     *
     * @param timestampMs sender-side monotonic ms — see [Packet.timestampMs]'s KDoc WARNING
     *   about NOT passing `System.currentTimeMillis()` here. Defaults to 0 to match this
     *   logic's pre-refactor behavior in `WifiUdpTransport` (which never set it either) —
     *   this default is a preserved pre-existing gap, not a new one; callers that have a real
     *   session-relative clock should pass it explicitly.
     */
    fun buildFragmentPackets(
        payload: ByteArray,
        sessionId: Int,
        senderShortId: Int,
        messageId: Int,
        startSequenceNumber: Int,
        langCode: Int,
        messageType: MessageType,
        isAlert: Boolean,
        maxFragmentPayload: Int,
        timestampMs: Long = 0L
    ): FragmentedMessage {
        val fragments = chunkPayload(payload, maxFragmentPayload)
        require(fragments.size <= 0xFF) { "message needs ${fragments.size} fragments, exceeds 1-byte field" }

        var seq = startSequenceNumber and 0xFFFF
        val packets = fragments.mapIndexed { idx, fragPayload ->
            var flags = if (fragments.size > 1) PacketFlags.FRAGMENTED else 0
            if (idx == fragments.size - 1) flags = flags or PacketFlags.IS_FINAL
            if (isAlert) flags = flags or PacketFlags.IS_ALERT

            val packet = Packet(
                flags = flags,
                sessionId = sessionId,
                senderShortId = senderShortId,
                messageId = messageId,
                sequenceNumber = seq,
                timestampMs = timestampMs,
                langCode = langCode,
                messageType = messageType,
                fragmentIndex = idx,
                fragmentCount = fragments.size,
                payload = fragPayload
            )
            seq = (seq + 1) and 0xFFFF
            packet
        }
        return FragmentedMessage(messageId, packets, seq)
    }
}
