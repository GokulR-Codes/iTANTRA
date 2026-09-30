// Standalone runtime smoke test for the new :orchestrator module — calls the REAL compiled
// classes (MessageOrchestrator, FakeInMemoryTransport, PacketFragmenter,
// SemanticPacketWireCodec, and the HEMIM classes from alert-engine) directly, exercising a
// complete two-endpoint send/receive/ACK round trip with no real network. Companion to
// alert-engine/validation/HemimSmokeTest.kt (which stops at classification and doesn't touch
// the wire), this test covers the full pipeline the driving prompt's §11/§16.4 asks for:
// text -> HEMIM -> priority -> encode -> fragment -> queue -> transport -> reassemble ->
// decode -> (re-)classify -> delivered.

import com.itantra.alert.EmergencyCategory
import com.itantra.core.PacketPriority
import com.itantra.orchestrator.DeliveryStatus
import com.itantra.orchestrator.FakeInMemoryTransport
import com.itantra.orchestrator.MessageOrchestrator
import com.itantra.protocol.PacketFormatException

private var checks = 0
private var failures = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (condition) println("PASS: $name") else { failures++; println("FAIL: $name") }
}

fun main() {
    println("=== :orchestrator end-to-end runtime smoke test ===")

    // --- Scenario 1: plain non-emergency message, full round trip including ACK ---
    run {
        val transportA = FakeInMemoryTransport()
        val transportB = FakeInMemoryTransport()
        transportA.linkTo(transportB)

        val sender = MessageOrchestrator(sessionId = 1, senderShortId = 100, transport = transportA)
        val receiver = MessageOrchestrator(sessionId = 1, senderShortId = 200, transport = transportB)

        var received: Pair<String, String>? = null
        receiver.onMessageReceived = { utterance, _ -> received = utterance.text to utterance.lang }

        val envelope = sender.sendText("Thanks for the update, see you tomorrow.", "en")

        check("plain message: receiver got the exact original text", received?.first == "Thanks for the update, see you tomorrow.")
        check("plain message: receiver got the correct language", received?.second == "en")
        check("plain message: envelope classified NORMAL_MESSAGE", envelope.emergencyAnalysis.category == EmergencyCategory.NORMAL_MESSAGE)
        check("plain message: mapped to NORMAL_BASE queue priority", envelope.semanticPacket.priority == PacketPriority.NORMAL_BASE)
        check("plain message: sender sees ACKNOWLEDGED after the real ACK round-trip", sender.deliveryStatus(envelope.messageId) == DeliveryStatus.ACKNOWLEDGED)
    }

    // --- Scenario 2: emergency message — priority mapping + independent receiver re-classification ---
    run {
        val transportA = FakeInMemoryTransport()
        val transportB = FakeInMemoryTransport()
        transportA.linkTo(transportB)
        val sender = MessageOrchestrator(sessionId = 2, senderShortId = 100, transport = transportA)
        val receiver = MessageOrchestrator(sessionId = 2, senderShortId = 200, transport = transportB)

        var receivedCategory: EmergencyCategory? = null
        receiver.onMessageReceived = { _, analysis -> receivedCategory = analysis.category }

        val envelope = sender.sendText("There is a fire near the school!", "en")

        check("emergency message: sender-side HEMIM classified FIRE", envelope.emergencyAnalysis.category == EmergencyCategory.FIRE)
        check("emergency message: mapped to ALERT_BASE queue priority", envelope.semanticPacket.priority == PacketPriority.ALERT_BASE)
        check("emergency message: receiver independently re-classified the SAME category from reconstructed text",
            receivedCategory == EmergencyCategory.FIRE)
        check("emergency message: sender ACKNOWLEDGED", sender.deliveryStatus(envelope.messageId) == DeliveryStatus.ACKNOWLEDGED)
    }

    // --- Scenario 3: message large enough to require multiple fragments ---
    run {
        val transportA = FakeInMemoryTransport()
        val transportB = FakeInMemoryTransport()
        transportA.linkTo(transportB)
        val sender = MessageOrchestrator(sessionId = 3, senderShortId = 100, transport = transportA, mtu = 80)
        val receiver = MessageOrchestrator(sessionId = 3, senderShortId = 200, transport = transportB, mtu = 80)

        val longText = "There is a fire near the market. ".repeat(20).trim()
        var received: String? = null
        receiver.onMessageReceived = { utterance, _ -> received = utterance.text }

        sender.sendText(longText, "en")

        check("fragmented message: multiple UDP-sized frames were actually sent (MTU=80 forces fragmentation)", transportA.sentFrameCount > 2)
        check("fragmented message: reassembled text exactly matches the original", received == longText)
    }

    // --- Scenario 4: corrupted frame is rejected, not crashed on, not delivered ---
    run {
        val transportA = FakeInMemoryTransport()
        val transportB = FakeInMemoryTransport()
        transportA.linkTo(transportB)
        val sender = MessageOrchestrator(sessionId = 4, senderShortId = 100, transport = transportA)
        val receiver = MessageOrchestrator(sessionId = 4, senderShortId = 200, transport = transportB)

        var delivered = false
        var decodeErrors = 0
        receiver.onMessageReceived = { _, _ -> delivered = true }
        receiver.onDecodeError = { decodeErrors++ }

        // Send garbage bytes directly at the receiver's transport, bypassing the real sender.
        transportA.send(byteArrayOf(1, 2, 3, 4, 5))

        check("corrupted frame: not delivered as a message", !delivered)
        check("corrupted frame: reported via onDecodeError, not swallowed", decodeErrors == 1)
    }

    // --- Scenario 5: a genuine network-level duplicate frame delivery is deduplicated ---
    run {
        val transportA = FakeInMemoryTransport()
        val transportB = FakeInMemoryTransport()
        transportA.linkTo(transportB)
        val sender = MessageOrchestrator(sessionId = 5, senderShortId = 100, transport = transportA)
        val receiver = MessageOrchestrator(sessionId = 5, senderShortId = 200, transport = transportB)

        var deliveredCount = 0
        receiver.onMessageReceived = { _, _ -> deliveredCount++ }

        sender.sendText("Please send help, we are stuck.", "en")
        check("duplicate frame: delivered once on first arrival", deliveredCount == 1)

        // Simulate a lower-layer duplicate delivery of the exact same already-sent frame
        // (e.g. a retransmission below our protocol) — same sequence number, same bytes.
        transportA.redeliverLastFrameToPeer()
        check("duplicate frame: NOT delivered a second time (ReplayWindow caught it)", deliveredCount == 1)
    }

    // --- Scenario 6: dropped ACK — sender must not claim ACKNOWLEDGED without a real ACK ---
    run {
        val transportA = FakeInMemoryTransport()
        val transportB = FakeInMemoryTransport()
        transportA.linkTo(transportB)
        val sender = MessageOrchestrator(sessionId = 7, senderShortId = 100, transport = transportA)
        val receiver = MessageOrchestrator(sessionId = 7, senderShortId = 200, transport = transportB)

        var delivered = false
        receiver.onMessageReceived = { _, _ -> delivered = true }

        transportB.dropNextFrame = true // drops the receiver's outgoing ACK, not the data
        val envelope = sender.sendText("Please send help.", "en")

        check("dropped ACK: message still reached the receiver", delivered)
        check("dropped ACK: sender does NOT claim ACKNOWLEDGED without a real ACK", sender.deliveryStatus(envelope.messageId) != DeliveryStatus.ACKNOWLEDGED)
        check("dropped ACK: sender status is TRANSMITTING, an honest in-flight state", sender.deliveryStatus(envelope.messageId) == DeliveryStatus.TRANSMITTING)
    }

    // --- Scenario 7: expiration via retryPending with a controllable clock ---
    run {
        var fakeNow = 1_000_000L
        val transportA = FakeInMemoryTransport()
        val sender = MessageOrchestrator(
            sessionId = 8, senderShortId = 100, transport = transportA,
            defaultTtlMs = 100, clock = { fakeNow }
        )
        // No peer linked: transport.send() is a no-op beyond bookkeeping (peer == null), so
        // no ACK will ever arrive — exactly what we want to force an expiration.
        val envelope = sender.sendText("unacknowledged forever", "en")
        check("expiration: starts QUEUED/TRANSMITTING, not already EXPIRED", sender.deliveryStatus(envelope.messageId) != DeliveryStatus.EXPIRED)

        fakeNow += 200 // past the 100ms TTL
        val retried = sender.retryPending(nowMs = fakeNow)

        check("expiration: expired entry is not counted as retried", retried == 0)
        check("expiration: status becomes EXPIRED once TTL has passed", sender.deliveryStatus(envelope.messageId) == DeliveryStatus.EXPIRED)
    }

    // --- Scenario 8: malformed input directly, no orchestrator peer at all ---
    run {
        val transportA = FakeInMemoryTransport()
        val transportB = FakeInMemoryTransport()
        transportA.linkTo(transportB)
        val receiver = MessageOrchestrator(sessionId = 9, senderShortId = 200, transport = transportB)
        var errors = 0
        receiver.onDecodeError = { errors++ }
        transportA.send(ByteArray(0)) // empty frame: must not crash
        transportA.send(ByteArray(3) { 0xFF.toByte() }) // too short to even have a header
        check("malformed input: two bad frames each reported, zero crashes", errors == 2)
    }

    println()
    println("=== $checks checks run, ${checks - failures} passed, $failures failed ===")
    if (failures > 0) kotlin.system.exitProcess(1)
}
