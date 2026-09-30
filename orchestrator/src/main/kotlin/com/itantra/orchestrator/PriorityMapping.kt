package com.itantra.orchestrator

import com.itantra.alert.EmergencyPriority
import com.itantra.core.PacketPriority

/**
 * Maps HEMIM's 6-tier [EmergencyPriority] onto the transport/reliability layer's 3-tier
 * [PacketPriority] — the exact mapping `docs/HEMIM.md` named as its own next step, now
 * implemented: **P0/P1/P2 -> ALERT_BASE, P3/P4 -> NORMAL_BASE, UNKNOWN -> NORMAL_BASE**.
 *
 * [EmergencyPriority.UNKNOWN] deliberately maps to `NORMAL_BASE`, not `RESIDUAL` —
 * `RetransmissionQueue` drops `RESIDUAL` first under queue pressure (see its KDoc), and an
 * uncertain classification must never be the thing preferentially dropped (HEMIM's own rule:
 * "Do not silently discard UNKNOWN messages" — this is that rule enforced at the queue
 * boundary, not just documented).
 */
object PriorityMapping {

    fun toPacketPriority(priority: EmergencyPriority): PacketPriority = when (priority) {
        EmergencyPriority.P0, EmergencyPriority.P1, EmergencyPriority.P2 -> PacketPriority.ALERT_BASE
        EmergencyPriority.P3, EmergencyPriority.P4 -> PacketPriority.NORMAL_BASE
        EmergencyPriority.UNKNOWN -> PacketPriority.NORMAL_BASE
    }

    /** Whether [Packet.flags][com.itantra.protocol.PacketFlags.IS_ALERT] should be set on the
     *  wire for a message with this [priority]. Currently identical to "maps to ALERT_BASE",
     *  kept as a separate named function because the two questions ("which queue tier" vs.
     *  "which wire flag") are conceptually distinct even though they agree today. */
    fun isAlertFlag(priority: EmergencyPriority): Boolean = toPacketPriority(priority) == PacketPriority.ALERT_BASE
}
