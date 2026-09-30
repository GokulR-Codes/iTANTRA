package com.itantra.protocol

import com.itantra.core.TransportKind

/**
 * Transport-agnostic send/receive contract (§17: Wi-Fi Adapter / BLE Adapter
 * rows). Kept in `protocol` (pure Kotlin) rather than in `wifi`/`bluetooth`
 * directly so Division B/D can write and test protocol logic (ACK policy,
 * FEC, the adaptive controller) against a fake implementation of this
 * interface without needing the Android SDK or real radios (§14's
 * dependency-decoupling principle, applied to transport the same way it's
 * applied to audio capture).
 */
interface PacketTransport {
    val kind: TransportKind

    fun send(frame: ByteArray)

    /** Registers a callback invoked on every successfully CRC-validated inbound frame. */
    fun onReceive(callback: (ByteArray) -> Unit)

    fun currentStats(): TransportLinkStats

    fun close()
}

/** Raw link-level telemetry a transport adapter can observe directly; feeds core.ChannelStats upstream (§14, §27). */
data class TransportLinkStats(
    val rttMs: Double?,
    val rssiDbm: Int?,
    val mtuBytes: Int,
    val connected: Boolean
)
