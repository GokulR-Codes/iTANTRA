package com.itantra.orchestrator

import com.itantra.core.TransportKind
import com.itantra.protocol.PacketTransport
import com.itantra.protocol.TransportLinkStats

/**
 * An in-memory, non-networked [PacketTransport]. Two instances can be linked with [linkTo] to
 * form a complete two-endpoint loopback pair — frames sent on one are delivered synchronously
 * to the other's registered `onReceive` callback, nothing goes over a real socket or radio.
 *
 * **What this honestly is and is not:** this is a real, working implementation of the
 * [PacketTransport] contract — sending and receiving genuinely happen — but it is not network
 * communication. It exists for:
 *  1. Unit/smoke-testing `:orchestrator` and anything built on it, exactly as driving-prompt
 *     §16.4 sanctions ("Where a real transport cannot be tested, use a clearly labeled local
 *     test transport and separately report the missing physical-device verification").
 *  2. A possible future single-device demo/self-test mode in the Android app (record a
 *     message, loop it back to yourself, see the full pipeline run) — not implemented here,
 *     just noted as a legitimate non-test use, so this class living in `main/` rather than
 *     `test/` is a deliberate choice, not scope creep.
 *
 * It must never be presented to a user as "connected to another device" — [currentStats]
 * reports `connected = true` because the loopback link genuinely is established, but callers
 * driving any UI must know this is a [LinkedPairs]-internal loopback, not a peer.
 */
class FakeInMemoryTransport(override val kind: TransportKind = TransportKind.WIFI) : PacketTransport {

    private var peer: FakeInMemoryTransport? = null
    private var receiveCallback: ((ByteArray) -> Unit)? = null
    private var closed = false
    var sentFrameCount: Int = 0
        private set
    var droppedFrameCount: Int = 0
        private set

    /** When true, [send] silently drops the next frame instead of delivering it, then resets
     *  to false — a deliberate, minimal way for tests to simulate a lost packet without
     *  needing a real lossy network. */
    var dropNextFrame: Boolean = false

    /** The exact bytes passed to the most recent [send] call (post drop-check), or null if
     *  none yet / the last call was dropped. Exists so tests can simulate a genuine
     *  network-level duplicate delivery via [redeliverLastFrameToPeer] — a real UDP/BLE link
     *  can deliver the same frame twice (retransmission at a lower layer, e.g.), and the
     *  protocol's [com.itantra.protocol.ReplayWindow] is what must catch that, not this test
     *  double pretending it can't happen. */
    var lastSentFrame: ByteArray? = null
        private set

    fun linkTo(other: FakeInMemoryTransport) {
        this.peer = other
        other.peer = this
    }

    /** Re-delivers [lastSentFrame] to [peer] again, as-is — simulates a lower-layer duplicate
     *  delivery of an already-sent frame, independent of and in addition to whatever this
     *  transport sends next. No-op if nothing has been sent yet. */
    fun redeliverLastFrameToPeer() {
        lastSentFrame?.let { peer?.receiveCallback?.invoke(it.copyOf()) }
    }

    override fun send(frame: ByteArray) {
        check(!closed) { "send() called after close()" }
        sentFrameCount++
        if (dropNextFrame) {
            dropNextFrame = false
            droppedFrameCount++
            return
        }
        // Copy the array: a real transport serializes to a socket/radio buffer, so a sender
        // mutating its own array after calling send() must never affect what the "other side"
        // sees — this loopback preserves that isolation instead of aliasing the same array.
        val copy = frame.copyOf()
        lastSentFrame = copy
        peer?.receiveCallback?.invoke(copy)
    }

    override fun onReceive(callback: (ByteArray) -> Unit) {
        receiveCallback = callback
    }

    override fun currentStats(): TransportLinkStats = TransportLinkStats(
        rttMs = 0.0,
        rssiDbm = null,
        mtuBytes = Int.MAX_VALUE,
        connected = peer != null && !closed
    )

    override fun close() {
        closed = true
        receiveCallback = null
    }
}
