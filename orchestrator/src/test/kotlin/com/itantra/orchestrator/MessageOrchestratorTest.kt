package com.itantra.orchestrator

import com.itantra.alert.EmergencyCategory
import com.itantra.core.PacketPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JUnit5 mirror of `orchestrator/validation/OrchestratorSmokeTest.kt`, which has already
 * been compiled and run for real in this sandbox (Maven Central/junit-jupiter jars are not
 * reachable here — see docs/KNOWN_ISSUES.md) with 22/22 checks passing after a real bug this
 * test coverage found and fixed (see `orchestrator/validation/RESULTS.md` and
 * `MessageOrchestrator.sendText`/`retryPending`'s inline comments on the ACKNOWLEDGED-vs-
 * TRANSMITTING ordering bug). This file has not itself been executed by Gradle in this
 * sandbox — same gap, same caveat as `alert-engine`'s JUnit files.
 */
class MessageOrchestratorTest {

    @Test
    fun `plain message round-trips and reaches ACKNOWLEDGED`() {
        val a = FakeInMemoryTransport()
        val b = FakeInMemoryTransport()
        a.linkTo(b)
        val sender = MessageOrchestrator(sessionId = 1, senderShortId = 100, transport = a)
        val receiver = MessageOrchestrator(sessionId = 1, senderShortId = 200, transport = b)

        var received: Pair<String, String>? = null
        receiver.onMessageReceived = { u, _ -> received = u.text to u.lang }

        val envelope = sender.sendText("Thanks for the update, see you tomorrow.", "en")

        assertEquals("Thanks for the update, see you tomorrow." to "en", received)
        assertEquals(EmergencyCategory.NORMAL_MESSAGE, envelope.emergencyAnalysis.category)
        assertEquals(PacketPriority.NORMAL_BASE, envelope.semanticPacket.priority)
        assertEquals(DeliveryStatus.ACKNOWLEDGED, sender.deliveryStatus(envelope.messageId))
    }

    @Test
    fun `emergency message maps to ALERT_BASE and receiver independently reclassifies the same category`() {
        val a = FakeInMemoryTransport()
        val b = FakeInMemoryTransport()
        a.linkTo(b)
        val sender = MessageOrchestrator(sessionId = 2, senderShortId = 100, transport = a)
        val receiver = MessageOrchestrator(sessionId = 2, senderShortId = 200, transport = b)

        var receivedCategory: EmergencyCategory? = null
        receiver.onMessageReceived = { _, analysis -> receivedCategory = analysis.category }

        val envelope = sender.sendText("There is a fire near the school!", "en")

        assertEquals(EmergencyCategory.FIRE, envelope.emergencyAnalysis.category)
        assertEquals(PacketPriority.ALERT_BASE, envelope.semanticPacket.priority)
        assertEquals(EmergencyCategory.FIRE, receivedCategory)
        assertEquals(DeliveryStatus.ACKNOWLEDGED, sender.deliveryStatus(envelope.messageId))
    }

    @Test
    fun `a message larger than one fragment reassembles exactly`() {
        val a = FakeInMemoryTransport()
        val b = FakeInMemoryTransport()
        a.linkTo(b)
        val sender = MessageOrchestrator(sessionId = 3, senderShortId = 100, transport = a, mtu = 80)
        val receiver = MessageOrchestrator(sessionId = 3, senderShortId = 200, transport = b, mtu = 80)

        val longText = "There is a fire near the market. ".repeat(20).trim()
        var received: String? = null
        receiver.onMessageReceived = { u, _ -> received = u.text }

        sender.sendText(longText, "en")

        assertTrue(a.sentFrameCount > 2, "expected fragmentation with a small MTU")
        assertEquals(longText, received)
    }

    @Test
    fun `a corrupted frame is rejected via onDecodeError, not delivered, and does not crash`() {
        val a = FakeInMemoryTransport()
        val b = FakeInMemoryTransport()
        a.linkTo(b)
        MessageOrchestrator(sessionId = 4, senderShortId = 100, transport = a)
        val receiver = MessageOrchestrator(sessionId = 4, senderShortId = 200, transport = b)

        var delivered = false
        var errors = 0
        receiver.onMessageReceived = { _, _ -> delivered = true }
        receiver.onDecodeError = { errors++ }

        a.send(byteArrayOf(1, 2, 3, 4, 5))

        assertTrue(!delivered)
        assertEquals(1, errors)
    }

    @Test
    fun `a genuinely duplicated frame is deduplicated by the replay window`() {
        val a = FakeInMemoryTransport()
        val b = FakeInMemoryTransport()
        a.linkTo(b)
        val sender = MessageOrchestrator(sessionId = 5, senderShortId = 100, transport = a)
        val receiver = MessageOrchestrator(sessionId = 5, senderShortId = 200, transport = b)

        var deliveredCount = 0
        receiver.onMessageReceived = { _, _ -> deliveredCount++ }

        sender.sendText("Please send help, we are stuck.", "en")
        assertEquals(1, deliveredCount)

        a.redeliverLastFrameToPeer()
        assertEquals(1, deliveredCount)
    }

    @Test
    fun `a dropped ACK leaves the sender honestly short of ACKNOWLEDGED`() {
        val a = FakeInMemoryTransport()
        val b = FakeInMemoryTransport()
        a.linkTo(b)
        val sender = MessageOrchestrator(sessionId = 6, senderShortId = 100, transport = a)
        val receiver = MessageOrchestrator(sessionId = 6, senderShortId = 200, transport = b)

        var delivered = false
        receiver.onMessageReceived = { _, _ -> delivered = true }

        b.dropNextFrame = true
        val envelope = sender.sendText("Please send help.", "en")

        assertTrue(delivered)
        assertNotEquals(DeliveryStatus.ACKNOWLEDGED, sender.deliveryStatus(envelope.messageId))
        assertEquals(DeliveryStatus.TRANSMITTING, sender.deliveryStatus(envelope.messageId))
    }

    @Test
    fun `an unacknowledged message expires after its TTL via retryPending`() {
        var fakeNow = 1_000_000L
        val a = FakeInMemoryTransport()
        val sender = MessageOrchestrator(
            sessionId = 7, senderShortId = 100, transport = a, defaultTtlMs = 100, clock = { fakeNow }
        )
        val envelope = sender.sendText("unacknowledged forever", "en")
        assertNotEquals(DeliveryStatus.EXPIRED, sender.deliveryStatus(envelope.messageId))

        fakeNow += 200
        val retried = sender.retryPending(nowMs = fakeNow)

        assertEquals(0, retried)
        assertEquals(DeliveryStatus.EXPIRED, sender.deliveryStatus(envelope.messageId))
    }

    @Test
    fun `malformed input never crashes the receiver`() {
        val a = FakeInMemoryTransport()
        val b = FakeInMemoryTransport()
        a.linkTo(b)
        val receiver = MessageOrchestrator(sessionId = 8, senderShortId = 200, transport = b)
        var errors = 0
        receiver.onDecodeError = { errors++ }

        a.send(ByteArray(0))
        a.send(ByteArray(3) { 0xFF.toByte() })

        assertEquals(2, errors)
    }
}
