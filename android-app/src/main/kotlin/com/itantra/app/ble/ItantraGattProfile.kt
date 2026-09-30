package com.itantra.app.ble

import java.util.UUID

/**
 * §11.2 — the custom GATT service definition both iTantra devices must agree on. Written
 * 2026-09-26, alongside [BleGattServerTransport]/[BleGattClientTransport]: the first real
 * `android.bluetooth.*` wiring in this repository (see those two classes' KDoc for the honest
 * scope/verification-status caveat that applies to this whole package — nothing here has run
 * against real BLE hardware).
 *
 * DESIGN, stated plainly:
 *  - One GATT **service**, one **inbound** characteristic (central writes into it; the
 *    peripheral's [android.bluetooth.BluetoothGattServerCallback.onCharacteristicWriteRequest]
 *    receives fragments this way) and one **outbound** characteristic (the peripheral
 *    *notifies* on it; the central's
 *    [android.bluetooth.BluetoothGattCallback.onCharacteristicChanged] receives fragments this
 *    way). Traffic is symmetric at the application layer (either side can originate a message)
 *    but asymmetric at the GATT/radio layer, because GATT itself only lets a central write to a
 *    peripheral and a peripheral notify a central — never the reverse. See
 *    [BleGattClientTransport]/[BleGattServerTransport]'s class docs for how each role maps onto
 *    [com.itantra.protocol.PacketTransport]'s single symmetric `send`/`onReceive` contract
 *    despite this.
 *  - There is deliberately no separate "ACK characteristic": an ACK is just another
 *    `MessageType.ACK` [com.itantra.protocol.Packet] (see `MessageOrchestrator.sendAck`),
 *    carried over whichever of the two characteristics already carries traffic in that
 *    direction. A dedicated ACK characteristic would duplicate that mechanism for no benefit —
 *    §11.2's "acknowledgement... characteristic" requirement is satisfied at the packet-protocol
 *    layer instead, which is also the layer Wi-Fi's ACKs go through, keeping the two transports
 *    consistent.
 *  - [STATUS_CHARACTERISTIC_UUID] is optional telemetry (§11.2's "optional status
 *    characteristic"): a single byte the peripheral keeps current, readable by the central
 *    without needing a full packet round-trip, currently just [ROLE_PERIPHERAL] as a liveness
 *    marker. Room to extend (e.g. a battery/queue-depth byte) without a wire-format version bump
 *    since it is deliberately NOT part of the §24 packet format.
 *  - One fragment = one already-encoded [com.itantra.protocol.Packet] (`Packet.encode()`'s
 *    output), one GATT write or notify per fragment — no additional framing on top of §24's own
 *    header/trailer. This mirrors Wi-Fi (one UDP datagram per fragment, see
 *    `WifiPacketTransportAdapter`) so the two transports produce interoperable wire bytes.
 *
 * UUIDs below are this project's own, generated once and fixed — both devices' installed APKs
 * must agree on them exactly (they are compiled constants, not negotiated at runtime).
 */
object ItantraGattProfile {
    /** Primary iTantra communication service (§11.2). */
    val SERVICE_UUID: UUID = UUID.fromString("6b1e9a10-6e6b-4b7a-9f0e-2f6b1c9a0001")

    /** Central writes fragments here; peripheral's GATT server receives them. */
    val INBOUND_CHARACTERISTIC_UUID: UUID = UUID.fromString("6b1e9a10-6e6b-4b7a-9f0e-2f6b1c9a0002")

    /** Peripheral notifies fragments here; central's GATT client receives them. */
    val OUTBOUND_CHARACTERISTIC_UUID: UUID = UUID.fromString("6b1e9a10-6e6b-4b7a-9f0e-2f6b1c9a0003")

    /** Optional single-byte liveness/status read (§11.2), not part of the §24 wire format. */
    val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("6b1e9a10-6e6b-4b7a-9f0e-2f6b1c9a0004")

    /** Standard Client Characteristic Configuration Descriptor UUID (BLE spec, not ours) —
     *  written by the central to enable notifications on [OUTBOUND_CHARACTERISTIC_UUID]. */
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val ROLE_PERIPHERAL: Byte = 0x01
}
