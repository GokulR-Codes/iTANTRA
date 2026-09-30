package com.itantra.app

import android.bluetooth.BluetoothDevice
import com.itantra.alert.EmergencyCategory
import com.itantra.alert.EmergencyPriority
import com.itantra.core.Language
import com.itantra.orchestrator.DeliveryStatus

/** Which [com.itantra.protocol.PacketTransport] backend a session should use — a user choice
 *  made on the (minimal, this-session) Connection screen, never auto-negotiated (matches
 *  `BleGattClientTransport`'s own KDoc: central-vs-peripheral is a user decision). */
sealed class TransportSelection {
    /** [remoteHost]/[remotePort] identify the peer to send to; [localPort] (0 = OS-assigned)
     *  is the local socket this device listens/sends on — both devices in a conversation
     *  configure each other's address, matching `WifiUdpTransport`'s own connect-oriented
     *  (not broadcast) design. */
    data class Wifi(val remoteHost: String, val remotePort: Int, val localPort: Int = 0) : TransportSelection()

    /** This device scans for a peer advertising the iTantra BLE service and connects to one
     *  the user picks from [SessionUiState.discoveredBleDevices]. */
    object BleCentral : TransportSelection()

    /** This device advertises the iTantra BLE service and waits for a central to connect. */
    object BlePeripheral : TransportSelection()
}

data class SessionConfig(
    val language: Language,
    val transport: TransportSelection,
    val sessionId: Int = 1,
    val senderShortId: Int = (0..0xFFFF).random()
)

data class DiscoveredBleDevice(
    val device: BluetoothDevice,
    val address: String,
    val name: String?,
    val rssiDbm: Int
)

/**
 * The complete, observable state of one session, exposed by [SessionService] as a
 * `StateFlow<SessionUiState>` (Module A's "expose message and session status to the Android
 * UI" requirement). Every field here reflects something a real component actually reported —
 * nothing is a placeholder/decorative value (Module L's "avoid fake connection indicators or
 * fabricated delivery confirmations").
 */
data class SessionUiState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val language: Language? = null,

    val sttAvailable: Boolean = false,
    val sttOfflineVerifiable: Boolean = false,

    val transportConnected: Boolean = false,
    val transportKindLabel: String? = null,

    val partialTranscript: String = "",

    val lastSentText: String? = null,
    val lastSentPriority: EmergencyPriority? = null,
    val lastSentCategory: EmergencyCategory? = null,
    val lastDeliveryStatus: DeliveryStatus? = null,

    val lastReceivedText: String? = null,
    val lastReceivedPriority: EmergencyPriority? = null,
    val lastReceivedRequiresReview: Boolean = false,

    val discoveredBleDevices: List<DiscoveredBleDevice> = emptyList(),

    val lastError: String? = null
)
