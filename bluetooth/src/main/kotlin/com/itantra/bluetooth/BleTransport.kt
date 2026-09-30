package com.itantra.bluetooth

import com.itantra.protocol.FragmentReassembler
import com.itantra.protocol.Packet
import com.itantra.protocol.PacketFormatException
import com.itantra.protocol.ReassembledMessage

/**
 * BLE GATT transport, §26: "runtime MTU discovery and negotiation (BLE
 * MTUs vary by device and OS version — do not hardcode one); fragmentation
 * sized to the negotiated MTU; windowed ACK scheme; residual packets
 * expire faster here than over Wi-Fi given BLE's lower throughput."
 *
 * HONEST SCOPE: unlike `wifi.WifiUdpTransport` (`DatagramSocket` is plain
 * `java.net` and its fragmentation scheme was proven against a real kernel
 * socket), BLE GATT (`android.bluetooth.*`) is Android-SDK-only with no
 * plain-JVM equivalent, and this integration's environment has no Android
 * SDK and no BLE hardware. Nothing in this file has been compiled against
 * the real Android BLE stack or executed on a device. What follows is a
 * real architectural design — the MTU-aware fragmentation math and the
 * windowed-ACK bookkeeping are pure logic, unit-tested here — wired to
 * explicit TODOs at every point that needs the actual
 * `BluetoothGatt`/`BluetoothGattServer` callback API, which cannot be
 * written correctly from first principles without a device to test
 * against (GATT callback ordering and MTU-negotiation quirks are
 * notoriously OEM/Android-version-dependent — guessing here would be
 * exactly the kind of fabricated-looking implementation this integration
 * avoids).
 */

enum class BleConnectionState { DISCONNECTED, SCANNING, CONNECTING, MTU_NEGOTIATING, CONNECTED, RECONNECTING }

data class BleTransportConfig(
    val requestedMtu: Int = 247, // BLE 4.2+ typical ceiling; ACTUAL negotiated value must come from the OS callback, never assumed
    val minimumViableMtu: Int = 23, // BLE 4.0 spec floor — the worst case the OS is allowed to hand back
    val ackWindowSize: Int = 8,
    val residualTtlFractionOfWifi: Float = 0.4f // §26: residual expires faster over BLE than Wi-Fi; exact fraction is [TARGET]
)

/**
 * ARCHITECTURAL FINDING (found via bluetooth/validation/ble_logic_reference.py, a real, run
 * Python reference; see docs/DECISIONS.md and docs/KNOWN_ISSUES.md): the §24 wire format's
 * 24-byte header + 2-byte CRC trailer, plus BLE's own 3-byte ATT protocol header, need a
 * negotiated ATT_MTU of at least 30 bytes to carry even ONE byte of payload. The BLE 4.0 spec
 * floor is 23 — strictly less than 30. A peer that never negotiates above the spec default
 * (old stacks, or one that declines the request) makes this wire format UNABLE TO TRANSMIT
 * ANYTHING over that link, not just inefficient. [MINIMUM_FUNCTIONAL_MTU] is the hard floor
 * this implementation actually requires; [BleTransportConfig.minimumViableMtu] above is only
 * the BLE *spec* floor and must not be confused with it.
 */
const val MINIMUM_FUNCTIONAL_MTU = 3 /* ATT header */ + Packet.HEADER_SIZE + Packet.TRAILER_SIZE + 1

/**
 * Pure-logic fragmentation sizing, reusable once a real negotiated MTU is known. Mirrors
 * WifiUdpTransport's fragmentation math exactly (same §24 header/trailer overhead accounting)
 * so the two transports produce interoperable wire packets regardless of which one carried them.
 */
object BleFragmenter {
    fun maxFragmentPayload(negotiatedMtu: Int, attHeaderOverhead: Int = 3): Int {
        // BLE ATT_MTU includes a 3-byte ATT protocol header that is NOT available for our
        // application payload — a real BLE constraint, not a §24 detail, and why BLE's usable
        // payload per packet is smaller than the same-numbered Wi-Fi MTU.
        val usableAtt = negotiatedMtu - attHeaderOverhead
        return usableAtt - Packet.HEADER_SIZE - Packet.TRAILER_SIZE
    }

    fun fragment(payload: ByteArray, negotiatedMtu: Int): List<ByteArray> {
        val maxPayload = maxFragmentPayload(negotiatedMtu)
        if (maxPayload <= 0) {
            // Fail loudly (§30 philosophy) rather than emit a negative- or zero-length
            // "fragment" that would corrupt the stream. This is exactly the
            // MINIMUM_FUNCTIONAL_MTU failure mode above.
            throw BleMtuTooSmallException(negotiatedMtu, MINIMUM_FUNCTIONAL_MTU)
        }
        if (payload.isEmpty()) return listOf(ByteArray(0))
        return payload.toList().chunked(maxPayload).map { it.toByteArray() }
    }
}

/**
 * Thrown when a negotiated BLE MTU cannot carry even one byte of payload under the current
 * §24 wire format. The caller (session orchestrator) must treat this as "BLE is not usable
 * for this peer" and either retry negotiation, prompt the user to use Wi-Fi instead, or invoke
 * a tier fallback — it must NOT be swallowed and silently produce no traffic.
 */
class BleMtuTooSmallException(val negotiatedMtu: Int, val requiredMtu: Int) : Exception(
    "Negotiated BLE ATT_MTU=$negotiatedMtu cannot carry the §24 header+trailer " +
        "(needs >= $requiredMtu). This link cannot transmit with the current wire format."
)

/**
 * Windowed ACK bookkeeping (§26). Tracks which sequence numbers within the current window
 * have been acknowledged, and how many are still in-flight, so the caller knows when it's
 * safe to slide the window forward. Pure logic, unit-testable without any BLE stack.
 */
class WindowedAckTracker(private val windowSize: Int) {
    private var windowStart = 0
    private val acked = mutableSetOf<Int>()

    fun windowRange(): IntRange = windowStart until (windowStart + windowSize)

    fun isWithinWindow(seq: Int): Boolean = seq in windowRange()

    fun markAcked(seq: Int) {
        if (isWithinWindow(seq)) acked.add(seq)
    }

    /** Slides the window forward past any leading run of acknowledged sequence numbers. */
    fun slide() {
        while (windowStart in acked) {
            acked.remove(windowStart)
            windowStart++
        }
    }

    fun inFlightCount(): Int = windowSize - acked.count { it in windowRange() }

    fun windowStartSeq(): Int = windowStart
}

class BleGattTransport(private val config: BleTransportConfig) {
    var state: BleConnectionState = BleConnectionState.DISCONNECTED
        private set

    private var negotiatedMtu: Int = config.minimumViableMtu
    private val ackTracker = WindowedAckTracker(config.ackWindowSize)

    // Added during the iTantra-Master merge: BleGattTransport had send-side fragmentation
    // (fragmentForSend) but, like the pre-fix WifiUdpTransport, no receive-side reassembly
    // hook at all. This reuses the same transport-agnostic FragmentReassembler as wifi, ready
    // for the day BluetoothGattCallback.onCharacteristicChanged is actually wired up to call it.
    private val reassembler = FragmentReassembler()

    fun negotiatedMtuOrDefault(): Int = negotiatedMtu

    /**
     * TODO(requires real Android BLE hardware/emulator): wire this to `BluetoothGatt.requestMtu()`
     * and the `onMtuChanged(gatt, mtu, status)` callback. The callback's reported `mtu` — NOT
     * `config.requestedMtu` — is the only source of truth for [negotiatedMtu]; §26 is explicit
     * that MTU must never be hardcoded. This method currently just demonstrates the intended
     * call shape and defaults to the conservative floor.
     */
    fun onMtuNegotiated(actualMtu: Int) {
        require(actualMtu >= config.minimumViableMtu) {
            "negotiated MTU $actualMtu is below the BLE 4.0 spec floor ${config.minimumViableMtu} — should be impossible"
        }
        if (actualMtu < MINIMUM_FUNCTIONAL_MTU) {
            // Spec-legal, but useless for this wire format — fail loudly now rather than let
            // the first send() throw later with less context.
            throw BleMtuTooSmallException(actualMtu, MINIMUM_FUNCTIONAL_MTU)
        }
        negotiatedMtu = actualMtu
        state = BleConnectionState.CONNECTED
    }

    fun fragmentForSend(payload: ByteArray): List<ByteArray> = BleFragmenter.fragment(payload, negotiatedMtu)

    /**
     * TODO(requires real Android BLE hardware): call this from
     * `BluetoothGattCallback.onCharacteristicChanged`/`onCharacteristicWrite` with each
     * notified/written chunk once that plumbing exists. Decodes one wire fragment and feeds it
     * through the shared reassembler; returns the complete message once every fragment for it
     * has arrived. Returns null both while a message is still incomplete AND when [rawChunk] is
     * malformed (fails closed per §30 — same policy as WifiUdpTransport.receiveOnce()).
     */
    fun onFragmentReceived(rawChunk: ByteArray, nowMs: Long): ReassembledMessage? {
        val packet = try {
            Packet.decode(rawChunk)
        } catch (e: PacketFormatException) {
            return null
        }
        return reassembler.onFragment(packet, nowMs)
    }

    fun residualTtlMs(wifiResidualTtlMs: Int): Int =
        (wifiResidualTtlMs * config.residualTtlFractionOfWifi).toInt()

    fun onAckReceived(sequenceNumber: Int) {
        ackTracker.markAcked(sequenceNumber)
        ackTracker.slide()
    }

    fun pendingAckCount(): Int = ackTracker.inFlightCount()

    // TODO(requires real Android BLE hardware): reconnect state machine equivalent to
    // WifiUdpTransport's, driven by BluetoothGattCallback.onConnectionStateChange with
    // exponential backoff — not written here because guessing at Android BLE reconnect timing
    // without a device to observe real GATT_ERROR / status codes on would be exactly the kind
    // of unvalidated assumption this integration avoids baking in as if it were settled.
}
