package com.itantra.orchestrator

import com.itantra.alert.EmergencyAnalysis
import com.itantra.core.SemanticPacket

/**
 * Wraps one outgoing message's [SemanticPacket] together with the [EmergencyAnalysis] that
 * decided its priority (driving prompt's "Layer 5/8" and the original HEMIM prompt's §16).
 *
 * This is **sender-side, local-only bookkeeping** — it does not travel over the wire.
 * [SemanticPacketWireCodec][com.itantra.protocol.SemanticPacketWireCodec] serializes only the
 * [semanticPacket]; [emergencyAnalysis] is never transmitted (see that object's KDoc for why:
 * the receiver independently re-runs HEMIM on the reconstructed text instead of trusting a
 * transmitted classification — see [MessageOrchestrator]'s receive path). This envelope's
 * job is to give the *sending* device's own UI something complete to show ("you sent a P0
 * FIRE message, here's why, here's its delivery status") without re-deriving the analysis
 * from scratch.
 *
 * [messageId] matches [com.itantra.protocol.Packet.messageId]'s 2-byte wire field, so it can
 * be used directly to correlate this envelope with the packets it produced, ACKs, and
 * [DeliveryTracker] entries.
 */
data class EmergencyMessageEnvelope(
    val messageId: Int,
    val semanticPacket: SemanticPacket,
    val emergencyAnalysis: EmergencyAnalysis,
    val createdAtMs: Long,
    val expiresAtMs: Long
) {
    init {
        require(messageId in 0..0xFFFF) { "messageId must fit the wire format's 2-byte field, got $messageId" }
        require(expiresAtMs >= createdAtMs) { "expiresAtMs ($expiresAtMs) must be >= createdAtMs ($createdAtMs)" }
    }
}
