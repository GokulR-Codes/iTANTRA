package com.itantra.protocol

/**
 * Reliability mechanisms named in §24: sequence-number-based
 * loss/reorder/duplicate detection, a replay window (the transport half of
 * §30's replay-protection requirement — the crypto/auth half is Gate 13
 * scope and lives in a future security/ module, not here), and a
 * priority-ordered retransmission queue that protects the semantic base
 * first and drops the residual first under pressure, per §24's explicit
 * priority order: ALERT_BASE > NORMAL_BASE > RESIDUAL.
 */

/**
 * Sliding-window duplicate/replay detector, per session (keyed externally
 * by sessionId — one instance per active session).
 *
 * A packet is accepted only if its sequence number is either newer than
 * anything seen, or within [windowSize] of the highest seen sequence AND
 * not already marked received. Anything older than the window, or already
 * seen, is rejected — this is what §24 means by "stale residuals are
 * dropped, never silently invented" applied to duplicate/replayed packets
 * in general, and it's also half of §30's replay-protection requirement
 * (paired with the packet timestamp field for a freshness check the
 * caller applies separately).
 */
class ReplayWindow(private val windowSize: Int = 64) {
    private var highestSeq: Int = -1
    private val seenBitmap = ArrayDeque<Boolean>() // index 0 == highestSeq

    /** Returns true if [seq] is newly accepted; false if it must be dropped. */
    @Synchronized
    fun accept(seq: Int): Boolean {
        if (highestSeq < 0) {
            highestSeq = seq
            seenBitmap.clear()
            seenBitmap.addFirst(true)
            return true
        }

        val diff = seq - highestSeq
        return when {
            diff > 0 -> {
                // New high-water mark: shift window forward, marking any
                // skipped sequence numbers as "not yet seen" (they may
                // still arrive late and be accepted within the window).
                repeat(diff - 1) { seenBitmap.addFirst(false) }
                seenBitmap.addFirst(true)
                while (seenBitmap.size > windowSize) seenBitmap.removeLast()
                highestSeq = seq
                true
            }
            diff == 0 -> false // exact duplicate of the newest packet
            -diff >= windowSize -> false // too old, outside the window — reject
            else -> {
                val idx = -diff
                if (idx >= seenBitmap.size) {
                    // Within nominal window range but we hadn't grown the
                    // deque this far yet (e.g. very first few packets) —
                    // pad with "not seen" up to idx, then accept.
                    while (seenBitmap.size <= idx) seenBitmap.addLast(false)
                }
                if (seenBitmap[idx]) {
                    false // already seen — duplicate
                } else {
                    seenBitmap[idx] = true
                    true
                }
            }
        }
    }
}

/**
 * An item awaiting acknowledgement or retransmission.
 * [priority] and [ttlMs] drive the "residual expires first, alert-base
 * never guessed at" behavior required by §24 and exercised by §36's
 * packet-loss test matrix.
 */
data class PendingPacket(
    val packet: Packet,
    val priority: com.itantra.core.PacketPriority,
    val enqueuedAtMs: Long,
    val ttlMs: Int,
    var attempts: Int = 0
) {
    fun isExpired(nowMs: Long): Boolean = nowMs - enqueuedAtMs > ttlMs
}

/**
 * Priority-ordered retransmission queue. Drains highest-priority,
 * non-expired packets first. Under queue pressure (bounded by
 * [maxQueueDepth]), drops RESIDUAL items before ever dropping a
 * NORMAL_BASE or ALERT_BASE item — this is the mechanical enforcement of
 * §24's "priority order alert-semantic-base > normal-semantic-base >
 * residual" and §12's Division B failure condition ("the residual layer
 * must never be allowed to silently degrade the semantic base's delivery
 * guarantee").
 */
class RetransmissionQueue(private val maxQueueDepth: Int = 256) {
    private val queue = mutableListOf<PendingPacket>()

    private val priorityRank = mapOf(
        com.itantra.core.PacketPriority.ALERT_BASE to 0,
        com.itantra.core.PacketPriority.NORMAL_BASE to 1,
        com.itantra.core.PacketPriority.RESIDUAL to 2
    )

    @Synchronized
    fun enqueue(item: PendingPacket) {
        if (queue.size >= maxQueueDepth) {
            val droppedResidualIdx = queue.indexOfLast {
                it.priority == com.itantra.core.PacketPriority.RESIDUAL
            }
            if (droppedResidualIdx >= 0) {
                queue.removeAt(droppedResidualIdx)
            } else if (item.priority == com.itantra.core.PacketPriority.RESIDUAL) {
                // Queue is full of non-residual traffic and this new item
                // is residual — drop the new item instead of an existing
                // higher-priority one.
                return
            } else {
                // Queue is full of ALERT_BASE/NORMAL_BASE and we still
                // need room for another non-residual item: drop the
                // single oldest NORMAL_BASE item if one exists, never an
                // ALERT_BASE item.
                val droppedNormalIdx = queue.indexOfLast {
                    it.priority == com.itantra.core.PacketPriority.NORMAL_BASE
                }
                if (droppedNormalIdx >= 0) queue.removeAt(droppedNormalIdx) else return
            }
        }
        queue.add(item)
        queue.sortBy { priorityRank.getValue(it.priority) }
    }

    /** Removes and returns expired entries so callers can log/count drops. */
    @Synchronized
    fun sweepExpired(nowMs: Long): List<PendingPacket> {
        val expired = queue.filter { it.isExpired(nowMs) }
        queue.removeAll(expired)
        return expired
    }

    @Synchronized
    fun acknowledge(messageId: Int) {
        queue.removeAll { it.packet.messageId == messageId }
    }

    @Synchronized
    fun nextBatch(limit: Int): List<PendingPacket> = queue.take(limit)

    @Synchronized
    fun size(): Int = queue.size
}

/**
 * Selective ACK tracker: the receiver's view of which (messageId,
 * fragmentIndex) pairs it has successfully reassembled, so it can tell
 * the sender exactly which fragments to retransmit rather than the whole
 * message.
 */
class SelectiveAckTracker {
    private val received = mutableMapOf<Int, MutableSet<Int>>() // messageId -> fragment indices

    @Synchronized
    fun markReceived(messageId: Int, fragmentIndex: Int) {
        received.getOrPut(messageId) { mutableSetOf() }.add(fragmentIndex)
    }

    @Synchronized
    fun missingFragments(messageId: Int, fragmentCount: Int): List<Int> {
        val have = received[messageId] ?: emptySet()
        return (0 until fragmentCount).filter { it !in have }
    }

    @Synchronized
    fun isComplete(messageId: Int, fragmentCount: Int): Boolean =
        missingFragments(messageId, fragmentCount).isEmpty()

    @Synchronized
    fun forget(messageId: Int) {
        received.remove(messageId)
    }
}
