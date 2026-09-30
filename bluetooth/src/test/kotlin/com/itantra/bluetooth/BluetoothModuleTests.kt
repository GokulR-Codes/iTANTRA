package com.itantra.bluetooth

import com.itantra.protocol.MessageType
import com.itantra.protocol.Packet
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BleFragmenterTest {

    @Test
    fun `MINIMUM_FUNCTIONAL_MTU is strictly greater than the BLE spec floor of 23`() {
        // This IS the architectural finding from ble_logic_reference.py: the BLE spec allows
        // a peer to never negotiate above 23 bytes, but this wire format cannot function below
        // MINIMUM_FUNCTIONAL_MTU. If this assertion ever fails, the finding has been lost.
        assertTrue(MINIMUM_FUNCTIONAL_MTU > 23, "MINIMUM_FUNCTIONAL_MTU=$MINIMUM_FUNCTIONAL_MTU must exceed the BLE 4.0 spec floor of 23")
    }

    @Test
    fun `fragmenting at the BLE spec floor MTU throws rather than emitting a corrupt fragment`() {
        assertThrows(BleMtuTooSmallException::class.java) {
            BleFragmenter.fragment("hi".toByteArray(), negotiatedMtu = 23)
        }
    }

    @Test
    fun `fragments correctly at a realistic negotiated MTU`() {
        val payload = "iTantra ".repeat(20).toByteArray()
        val fragments = BleFragmenter.fragment(payload, negotiatedMtu = 100)
        val reconstructed = fragments.fold(ByteArray(0)) { acc, f -> acc + f }
        assertArrayEquals(payload, reconstructed)
        assertTrue(fragments.size > 1, "a 160-byte payload at MTU=100 should need multiple fragments")
    }

    @Test
    fun `empty payload still produces exactly one (empty) fragment`() {
        val fragments = BleFragmenter.fragment(ByteArray(0), negotiatedMtu = 100)
        assertEquals(1, fragments.size)
        assertEquals(0, fragments[0].size)
    }
}

class WindowedAckTrackerTest {

    @Test
    fun `window slides forward only past a contiguous leading run of acks`() {
        val tracker = WindowedAckTracker(windowSize = 4)
        assertEquals(0, tracker.windowStartSeq())

        tracker.markAcked(1) // out of order — 0 not yet acked
        tracker.slide()
        assertEquals(0, tracker.windowStartSeq(), "must not slide past a gap at the window start")

        tracker.markAcked(0)
        tracker.slide()
        assertEquals(2, tracker.windowStartSeq(), "0 and 1 both acked, contiguous, should slide past both")
    }

    @Test
    fun `inFlightCount decreases as acks arrive`() {
        val tracker = WindowedAckTracker(windowSize = 4)
        assertEquals(4, tracker.inFlightCount())
        tracker.markAcked(0)
        assertEquals(3, tracker.inFlightCount())
    }

    @Test
    fun `ack outside the current window is ignored`() {
        val tracker = WindowedAckTracker(windowSize = 4)
        tracker.markAcked(99) // far outside [0,4)
        tracker.slide()
        assertEquals(0, tracker.windowStartSeq(), "an out-of-window ack must not affect the window at all")
    }
}

class BleGattTransportTest {

    @Test
    fun `onMtuNegotiated below MINIMUM_FUNCTIONAL_MTU throws rather than silently accepting it`() {
        val transport = BleGattTransport(BleTransportConfig(minimumViableMtu = 23))
        assertThrows(BleMtuTooSmallException::class.java) {
            transport.onMtuNegotiated(23) // spec-legal, but functionally too small
        }
    }

    @Test
    fun `onMtuNegotiated above the functional floor moves state to CONNECTED`() {
        val transport = BleGattTransport(BleTransportConfig())
        transport.onMtuNegotiated(185)
        assertEquals(BleConnectionState.CONNECTED, transport.state)
        assertEquals(185, transport.negotiatedMtuOrDefault())
    }

    @Test
    fun `residualTtlMs is a strict fraction of the equivalent Wi-Fi ttl`() {
        val transport = BleGattTransport(BleTransportConfig(residualTtlFractionOfWifi = 0.4f))
        assertEquals(600, transport.residualTtlMs(wifiResidualTtlMs = 1500))
    }

    @Test
    fun `onFragmentReceived reassembles a single-fragment message via the shared reassembler`() {
        val transport = BleGattTransport(BleTransportConfig())
        val packet = Packet(
            flags = 0, sessionId = 1, senderShortId = 1, messageId = 1, sequenceNumber = 0,
            timestampMs = 0, langCode = 0, messageType = MessageType.FINAL,
            fragmentIndex = 0, fragmentCount = 1, payload = "ble-hello".toByteArray()
        )
        val result = transport.onFragmentReceived(packet.encode(), nowMs = 0)
        assertArrayEquals("ble-hello".toByteArray(), result!!.payload)
    }

    @Test
    fun `onFragmentReceived returns null for a corrupted chunk rather than throwing`() {
        val transport = BleGattTransport(BleTransportConfig())
        assertEquals(null, transport.onFragmentReceived(byteArrayOf(1, 2, 3), nowMs = 0))
    }
}
