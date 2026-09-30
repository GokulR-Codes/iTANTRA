package com.itantra.orchestrator

/**
 * User/UI-visible delivery lifecycle for one outgoing message (messageId-keyed).
 *
 * Never display "delivered"/[ACKNOWLEDGED] unless a real ACK packet was actually received —
 * this is the enforcement point for that rule, not the UI layer (a UI that reads this enum
 * and displays it faithfully cannot fabricate delivery).
 */
enum class DeliveryStatus {
    CREATED, QUEUED, TRANSMITTING, ACKNOWLEDGED, RETRYING, EXPIRED, FAILED, UNKNOWN
}

/**
 * Tracks [DeliveryStatus] per messageId. Deliberately separate from [EmergencyMessageEnvelope]
 * (which is an immutable data class) rather than a mutable field on it — delivery status
 * changes over the message's lifetime and is orchestrator-local bookkeeping, not part of the
 * envelope's own identity/content.
 */
class DeliveryTracker {
    private val statuses = mutableMapOf<Int, DeliveryStatus>()

    @Synchronized
    fun set(messageId: Int, status: DeliveryStatus) {
        statuses[messageId] = status
    }

    @Synchronized
    fun get(messageId: Int): DeliveryStatus = statuses[messageId] ?: DeliveryStatus.UNKNOWN

    @Synchronized
    fun snapshot(): Map<Int, DeliveryStatus> = statuses.toMap()
}
