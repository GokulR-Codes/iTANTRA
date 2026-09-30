package com.itantra.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketFormatTest {

    private fun samplePacket(payload: ByteArray = "hello".toByteArray()) = Packet(
        flags = PacketFlags.IS_FINAL,
        sessionId = 42,
        senderShortId = 7,
        messageId = 100,
        sequenceNumber = 1,
        timestampMs = 123_456L,
        langCode = 5,
        messageType = MessageType.FINAL,
        fragmentIndex = 0,
        fragmentCount = 1,
        payload = payload
    )

    @Test
    fun `header size is exactly 24 bytes per §24's resolved header-vs-trailer inconsistency`() {
        assertEquals(24, Packet.HEADER_SIZE)
    }

    @Test
    fun `round-trips a well-formed packet exactly`() {
        val original = samplePacket()
        val decoded = Packet.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trips an empty payload`() {
        val original = samplePacket(ByteArray(0))
        val decoded = Packet.decode(original.encode())
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `flag bits decode correctly`() {
        val p = samplePacket().copy(flags = PacketFlags.IS_ALERT or PacketFlags.REQUIRES_ACK)
        val decoded = Packet.decode(p.encode())
        assertTrue(decoded.isAlert)
        assertTrue(decoded.requiresAck)
        assertFalse(decoded.isFragmented)
    }

    @Test
    fun `truncated packet fails closed, never returns a partial result`() {
        val bytes = samplePacket().encode()
        assertThrows(PacketFormatException::class.java) {
            Packet.decode(bytes.copyOf(10))
        }
    }

    @Test
    fun `bad magic nibble fails closed`() {
        val bytes = samplePacket().encode()
        bytes[0] = 0x00
        assertThrows(PacketFormatException::class.java) { Packet.decode(bytes) }
    }

    @Test
    fun `single-bit corruption in payload is caught by CRC`() {
        val bytes = samplePacket().encode()
        val payloadStart = Packet.HEADER_SIZE
        bytes[payloadStart] = (bytes[payloadStart].toInt() xor 0x01).toByte()
        assertThrows(PacketFormatException::class.java) { Packet.decode(bytes) }
    }

    @Test
    fun `unknown message type code fails closed rather than guessing`() {
        assertThrows(PacketFormatException::class.java) { MessageType.fromCode(0xFE) }
    }

    @Test
    fun `oversized payload is rejected at construction, not left to wrap silently`() {
        assertThrows(IllegalArgumentException::class.java) {
            samplePacket(ByteArray(0x10000))
        }
    }
}

class Crc16Test {
    @Test
    fun `same bytes always produce the same CRC`() {
        val bytes = "iTantra".toByteArray()
        assertEquals(Crc16.compute(bytes), Crc16.compute(bytes))
    }

    @Test
    fun `changing one byte changes the CRC`() {
        val a = "iTantra".toByteArray()
        val b = "iTantrb".toByteArray()
        assertTrue(Crc16.compute(a) != Crc16.compute(b))
    }
}

class ReliabilityLayerTest {

    @Test
    fun `ReplayWindow accepts a fresh sequence number once and rejects the exact duplicate`() {
        val window = ReplayWindow()
        assertTrue(window.accept(1))
        assertFalse(window.accept(1), "exact duplicate must be rejected")
        assertTrue(window.accept(2))
    }

    @Test
    fun `ReplayWindow tolerates reasonable out-of-order arrival within the window`() {
        val window = ReplayWindow()
        assertTrue(window.accept(5))
        assertTrue(window.accept(3), "3 arriving after 5 is out-of-order but within window, must be accepted once")
        assertFalse(window.accept(3), "but not twice")
    }

    private fun pending(messageId: Int, priority: com.itantra.core.PacketPriority, ttlMs: Int = 10_000) = PendingPacket(
        packet = Packet(
            flags = 0, sessionId = 1, senderShortId = 1, messageId = messageId, sequenceNumber = 0,
            timestampMs = 0, langCode = 0, messageType = MessageType.FINAL, fragmentIndex = 0,
            fragmentCount = 1, payload = ByteArray(0)
        ),
        priority = priority, enqueuedAtMs = 0, ttlMs = ttlMs
    )

    @Test
    fun `RetransmissionQueue drops RESIDUAL items before ever dropping ALERT_BASE or NORMAL_BASE`() {
        val queue = RetransmissionQueue(maxQueueDepth = 2)
        queue.enqueue(pending(1, com.itantra.core.PacketPriority.ALERT_BASE))
        queue.enqueue(pending(2, com.itantra.core.PacketPriority.RESIDUAL))
        assertEquals(2, queue.size())

        // Queue is full; a new NORMAL_BASE item arrives. The RESIDUAL item must be dropped
        // to make room, never the ALERT_BASE item.
        queue.enqueue(pending(3, com.itantra.core.PacketPriority.NORMAL_BASE))
        assertEquals(2, queue.size(), "queue must stay at maxQueueDepth, not grow unbounded")
        val remainingIds = queue.nextBatch(10).map { it.packet.messageId }.toSet()
        assertTrue(1 in remainingIds, "ALERT_BASE item must never be dropped to make room")
        assertFalse(2 in remainingIds, "RESIDUAL item must be dropped first under pressure")
    }

    @Test
    fun `acknowledge removes the matching item by messageId`() {
        val queue = RetransmissionQueue()
        queue.enqueue(pending(1, com.itantra.core.PacketPriority.NORMAL_BASE))
        queue.acknowledge(1)
        assertEquals(0, queue.size())
    }

    @Test
    fun `sweepExpired removes and returns only expired entries`() {
        val queue = RetransmissionQueue()
        queue.enqueue(pending(1, com.itantra.core.PacketPriority.NORMAL_BASE, ttlMs = 100))
        val expired = queue.sweepExpired(nowMs = 500)
        assertEquals(1, expired.size)
        assertEquals(0, queue.size())
    }
}

class SelectiveAckTrackerTest {
    @Test
    fun `tracks missing fragments until all are marked received`() {
        val tracker = SelectiveAckTracker()
        tracker.markReceived(messageId = 1, fragmentIndex = 0)
        tracker.markReceived(messageId = 1, fragmentIndex = 2)
        assertFalse(tracker.isComplete(1, fragmentCount = 3))
        assertEquals(listOf(1), tracker.missingFragments(1, fragmentCount = 3))

        tracker.markReceived(messageId = 1, fragmentIndex = 1)
        assertTrue(tracker.isComplete(1, fragmentCount = 3))
    }
}
