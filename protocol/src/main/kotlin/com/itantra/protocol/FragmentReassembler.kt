package com.itantra.protocol

/**
 * NEW FILE (not ported from any source archive). The master integration prompt
 * specifically flagged this as a likely gap, and inspection confirmed it: Part IV's
 * `WifiUdpTransport.send()` fragments an outbound payload into multiple [Packet]s
 * (§24/§25), but `receiveOnce()` only ever returns one fragment [Packet] at a time —
 * nothing on the receive side collects fragments back into a complete message. This
 * class is that missing piece. It is transport-agnostic (works for wifi and, once
 * wired, bluetooth) since it operates purely on decoded [Packet]s.
 *
 * Responsibilities (per the reassembler requirements the audit was built against):
 * session + message + fragment-index tracking, out-of-order and duplicate fragment
 * tolerance, missing-fragment handling, timeout/expiration, a memory/message-count
 * bound, maximum message size, thread safety, and a clear result type. CRC validation
 * of each individual fragment already happened in [Packet.decode] before a fragment
 * ever reaches this class — this class's job is purely reassembly bookkeeping.
 */
data class ReassembledMessage(
    val sessionId: Int,
    val messageId: Int,
    val messageType: MessageType,
    val langCode: Int,
    val isAlert: Boolean,
    val payload: ByteArray
)

class FragmentReassembler(
    private val maxConcurrentMessages: Int = 32,
    private val maxMessageBytes: Int = 1_000_000,
    private val fragmentTimeoutMs: Long = 10_000
) {
    private class InFlightMessage(
        val sessionId: Int,
        val fragmentCount: Int,
        val fragments: Array<ByteArray?>,
        val firstSeenAtMs: Long,
        val messageType: MessageType,
        val langCode: Int,
        val isAlert: Boolean
    ) {
        var receivedCount: Int = 0
        var totalBytes: Int = 0
        var lastFragmentAtMs: Long = firstSeenAtMs
    }

    // Key packs (sessionId, messageId) into one Long: messageId is contractually bounded to
    // 0..0xFFFF by Packet's own init check, so it never collides with sessionId's high bits.
    private fun key(sessionId: Int, messageId: Int): Long =
        (sessionId.toLong() shl 32) or (messageId.toLong() and 0xFFFFFFFFL)

    private val inFlight = LinkedHashMap<Long, InFlightMessage>()

    /**
     * Feeds one already-CRC-validated, already-decoded fragment [Packet]. Returns the
     * complete [ReassembledMessage] the moment the last missing fragment arrives, or null
     * if the message is still incomplete. A single-fragment message (fragmentCount == 1)
     * completes immediately on its first (only) fragment.
     */
    @Synchronized
    fun onFragment(packet: Packet, nowMs: Long): ReassembledMessage? {
        require(packet.fragmentCount > 0) { "fragmentCount must be positive, got ${packet.fragmentCount}" }
        require(packet.fragmentIndex in 0 until packet.fragmentCount) {
            "fragmentIndex ${packet.fragmentIndex} out of range for fragmentCount ${packet.fragmentCount}"
        }

        val k = key(packet.sessionId, packet.messageId)
        var msg = inFlight[k]

        if (msg == null) {
            if (inFlight.size >= maxConcurrentMessages) evictOldest()
            msg = InFlightMessage(
                sessionId = packet.sessionId,
                fragmentCount = packet.fragmentCount,
                fragments = arrayOfNulls(packet.fragmentCount),
                firstSeenAtMs = nowMs,
                messageType = packet.messageType,
                langCode = packet.langCode,
                isAlert = packet.isAlert
            )
            inFlight[k] = msg
        }

        if (packet.fragmentCount != msg.fragmentCount) {
            // A fragment claiming a different total fragment count for an already-tracked
            // (sessionId, messageId) pair is malformed/inconsistent — drop just this
            // fragment rather than corrupting or crashing the in-flight reassembly.
            return null
        }

        if (msg.fragments[packet.fragmentIndex] == null) {
            msg.fragments[packet.fragmentIndex] = packet.payload
            msg.receivedCount++
            msg.totalBytes += packet.payload.size
            if (msg.totalBytes > maxMessageBytes) {
                inFlight.remove(k)
                throw IllegalStateException(
                    "Reassembled message would exceed maxMessageBytes=$maxMessageBytes " +
                        "(sessionId=${packet.sessionId}, messageId=${packet.messageId}) — dropped, not truncated silently."
                )
            }
        } // else: exact duplicate fragment, ignored (idempotent)

        msg.lastFragmentAtMs = nowMs

        if (msg.receivedCount < msg.fragmentCount) return null

        inFlight.remove(k)
        val combined = ByteArray(msg.totalBytes)
        var offset = 0
        for (frag in msg.fragments) {
            frag!!.copyInto(combined, offset) // safe: receivedCount == fragmentCount, so no nulls remain
            offset += frag.size
        }
        return ReassembledMessage(
            sessionId = msg.sessionId,
            messageId = packet.messageId,
            messageType = msg.messageType,
            langCode = msg.langCode,
            isAlert = msg.isAlert,
            payload = combined
        )
    }

    /** Drops and reports any message that hasn't received a new fragment within the timeout. */
    @Synchronized
    fun sweepExpired(nowMs: Long): List<Pair<Int, Int>> {
        val expiredKeys = inFlight.entries
            .filter { (_, v) -> nowMs - v.lastFragmentAtMs > fragmentTimeoutMs }
            .map { it.key }
        val result = expiredKeys.map { k ->
            val v = inFlight.getValue(k)
            v.sessionId to (k and 0xFFFFFFFFL).toInt()
        }
        expiredKeys.forEach { inFlight.remove(it) }
        return result
    }

    private fun evictOldest() {
        val oldestKey = inFlight.entries.minByOrNull { it.value.firstSeenAtMs }?.key
        if (oldestKey != null) inFlight.remove(oldestKey)
    }

    @Synchronized
    fun activeMessageCount(): Int = inFlight.size
}
