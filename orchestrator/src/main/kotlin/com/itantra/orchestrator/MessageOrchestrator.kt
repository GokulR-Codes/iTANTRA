package com.itantra.orchestrator

import com.itantra.alert.EmergencyAnalysis
import com.itantra.alert.EmergencyAnalyzer
import com.itantra.alert.EmergencyAnalyzerFactory
import com.itantra.codec.SemanticCodec
import com.itantra.codec.Utf8BaselineCodec
import com.itantra.core.PacketPriority
import com.itantra.core.ReceivedUtterance
import com.itantra.core.RecognizedUtterance
import com.itantra.protocol.FragmentReassembler
import com.itantra.protocol.MessageType
import com.itantra.protocol.Packet
import com.itantra.protocol.PacketFormatException
import com.itantra.protocol.PacketFragmenter
import com.itantra.protocol.PacketTransport
import com.itantra.protocol.PendingPacket
import com.itantra.protocol.ReplayWindow
import com.itantra.protocol.RetransmissionQueue
import com.itantra.protocol.SemanticPacketWireCodec

/**
 * The real, wired-together message pipeline this repository was missing before this session
 * (see [SemanticPacketWireCodec]'s KDoc for how that gap was found). One instance owns one
 * session's worth of state on one side of a conversation.
 *
 * **Outgoing** ([sendText]): HEMIM classify -> map to [PacketPriority] ->
 * [SemanticCodec.encode] -> [SemanticPacketWireCodec.encodePayload] ->
 * [PacketFragmenter.buildFragmentPackets] -> [RetransmissionQueue.enqueue] ->
 * [PacketTransport.send].
 *
 * **Incoming** (wired automatically via [PacketTransport.onReceive] in [init]):
 * [Packet.decode] -> ACK short-circuit, or [ReplayWindow.accept] -> [FragmentReassembler] ->
 * (on completion) send an ACK back -> [SemanticPacketWireCodec.decodePayload] ->
 * [SemanticCodec.decode] -> **independently re-run HEMIM on the receiving device** -> deliver
 * both to [onMessageReceived].
 *
 * Design decisions stated plainly, not left implicit:
 *  - **Synchronous draining.** [sendText] calls [PacketTransport.send] directly, in the same
 *    call, rather than handing packets to a background sender loop — there is no coroutine
 *    dependency anywhere in this module (deliberately, so it stays compilable/testable in a
 *    plain-JVM sandbox with no Android SDK; see `orchestrator/build.gradle.kts`). A real
 *    Android session service driving retries/backoff on its own IO loop would call
 *    [retryPending] periodically instead of relying on this class to schedule anything
 *    itself — this class provides the mechanism, not a scheduler.
 *  - **The sender's [EmergencyAnalysis] never crosses the wire.** Only the coarse
 *    [Packet.isAlert] bit does. The receiving device re-runs [EmergencyAnalyzer.analyze] on
 *    the reconstructed text itself once decoded. HEMIM is deterministic and fully offline, so
 *    this costs nothing and means the receiver's own alerting UI never has to blindly trust a
 *    number the sender computed — see [EmergencyMessageEnvelope]'s KDoc for the same point
 *    from the sender's side.
 *  - **[sessionStartMs]** is captured once, at construction, and used to compute the
 *    session-relative value written into [Packet.timestampMs] — seeing
 *    [PacketFragmenter.buildFragmentPackets]'s default silently write 0 for every packet
 *    would have repeated [Packet]'s own documented wire-truncation bug rather than fixing it
 *    where a caller actually has a real clock to give it.
 *
 * **What this class does NOT do** (out of scope for a pure-JVM module, tracked in
 * `docs/ORCHESTRATOR.md`): real network/radio I/O (needs `wifi`/`bluetooth`, both
 * Android/coroutine-dependent), real STT/TTS (no engine exists in this repo yet — see
 * `docs/HEMIM.md`), congestion/backoff policy beyond the single [retryPending] sweep,
 * encryption (CRC16 here is corruption detection only, not authentication — see
 * `docs/KNOWN_ISSUES.md`).
 */
class MessageOrchestrator(
    private val sessionId: Int,
    private val senderShortId: Int,
    private val transport: PacketTransport,
    private val emergencyAnalyzer: EmergencyAnalyzer = EmergencyAnalyzerFactory.createDefault(),
    private val codec: SemanticCodec = Utf8BaselineCodec(),
    private val mtu: Int = 1200,
    private val defaultTtlMs: Int = 30_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val sessionStartMs: Long = clock()

    private var nextMessageId = 0
    private var nextSequenceNumber = 0
    private var nextSentenceId = 0L

    private val retransmissionQueue = RetransmissionQueue()
    private val reassembler = FragmentReassembler()
    private val replayWindow = ReplayWindow()
    private val deliveryTracker = DeliveryTracker()

    /** Invoked once per fully-reassembled, successfully-decoded inbound message, with the
     *  receiving device's own independent HEMIM re-classification attached. */
    var onMessageReceived: ((ReceivedUtterance, EmergencyAnalysis) -> Unit)? = null

    /** Invoked on a corrupt/truncated inbound frame or payload — the message is dropped
     *  (never delivered partially or guessed at), never silently swallowed. */
    var onDecodeError: ((PacketFormatException) -> Unit)? = null

    init {
        transport.onReceive { bytes -> handleIncoming(bytes, clock()) }
    }

    /**
     * Classifies [text] with HEMIM, encodes, fragments, enqueues, and immediately transmits
     * every resulting fragment. Returns the sender-local [EmergencyMessageEnvelope] for the
     * caller's own UI/bookkeeping (see that class's KDoc).
     */
    fun sendText(text: String, lang: String): EmergencyMessageEnvelope {
        val nowMs = clock()
        val analysis = emergencyAnalyzer.analyze(text, lang)
        val packetPriority = PriorityMapping.toPacketPriority(analysis.priority)
        val isAlert = PriorityMapping.isAlertFlag(analysis.priority)

        val sentenceId = nextSentenceId
        nextSentenceId++
        val utterance = RecognizedUtterance(
            text = text, lang = lang, isFinal = true, confidence = 1f, startTsMs = nowMs, endTsMs = nowMs
        )
        val semanticPacket = codec.encode(utterance, packetPriority, defaultTtlMs, sentenceId)

        val messageId = nextMessageId
        nextMessageId = (nextMessageId + 1) and 0xFFFF

        val envelope = EmergencyMessageEnvelope(
            messageId = messageId,
            semanticPacket = semanticPacket,
            emergencyAnalysis = analysis,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + defaultTtlMs
        )
        deliveryTracker.set(messageId, DeliveryStatus.CREATED)

        val payloadBytes = SemanticPacketWireCodec.encodePayload(semanticPacket)
        val langCode = SemanticPacketWireCodec.langCodeFor(lang)
        val maxFragmentPayload = PacketFragmenter.maxFragmentPayload(mtu)

        val fragmented = PacketFragmenter.buildFragmentPackets(
            payload = payloadBytes,
            sessionId = sessionId,
            senderShortId = senderShortId,
            messageId = messageId,
            startSequenceNumber = nextSequenceNumber,
            langCode = langCode,
            messageType = if (isAlert) MessageType.ALERT else MessageType.FINAL,
            isAlert = isAlert,
            maxFragmentPayload = maxFragmentPayload,
            timestampMs = nowMs - sessionStartMs
        )
        nextSequenceNumber = fragmented.nextSequenceNumber

        fragmented.packets.forEach { p ->
            retransmissionQueue.enqueue(PendingPacket(p, packetPriority, nowMs, defaultTtlMs))
        }
        deliveryTracker.set(messageId, DeliveryStatus.QUEUED)

        val batch = retransmissionQueue.nextBatch(fragmented.packets.size)
        batch.forEach { pending -> transport.send(pending.packet.encode()) }
        // BUG FOUND VIA REAL EXECUTION (orchestrator/validation/RESULTS.md scenario 1/2): a
        // synchronous transport (e.g. FakeInMemoryTransport, and potentially a same-process
        // transport in general) can run the receiver's entire handleIncoming -> sendAck ->
        // this sender's own handleIncoming(ACK) chain *inside* the transport.send() call
        // above, setting ACKNOWLEDGED before this line ever runs. Unconditionally writing
        // TRANSMITTING here would silently regress that back — an honest delivery-status
        // system must never move backwards in the lifecycle, so only advance to TRANSMITTING
        // if nothing further along (an ACK) has already been recorded.
        if (deliveryTracker.get(messageId) != DeliveryStatus.ACKNOWLEDGED) {
            deliveryTracker.set(messageId, DeliveryStatus.TRANSMITTING)
        }

        return envelope
    }

    fun deliveryStatus(messageId: Int): DeliveryStatus = deliveryTracker.get(messageId)

    /**
     * Sweeps expired queue entries (marking them [DeliveryStatus.EXPIRED]) and re-transmits
     * up to [limit] still-pending entries (marking them [DeliveryStatus.RETRYING]). A real
     * Android session service is expected to call this on its own periodic timer — see class
     * doc's "Synchronous draining" note. Returns the number of packets actually retransmitted.
     */
    fun retryPending(nowMs: Long = clock(), limit: Int = 16): Int {
        val expired = retransmissionQueue.sweepExpired(nowMs)
        expired.forEach { deliveryTracker.set(it.packet.messageId, DeliveryStatus.EXPIRED) }

        val batch = retransmissionQueue.nextBatch(limit)
        batch.forEach { pending ->
            pending.attempts++
            transport.send(pending.packet.encode())
            // Same hazard as sendText() (see its comment): a synchronous transport can
            // acknowledge this exact message inside the send() call above.
            if (deliveryTracker.get(pending.packet.messageId) != DeliveryStatus.ACKNOWLEDGED) {
                deliveryTracker.set(pending.packet.messageId, DeliveryStatus.RETRYING)
            }
        }
        return batch.size
    }

    fun close() = transport.close()

    private fun handleIncoming(bytes: ByteArray, nowMs: Long) {
        val packet = try {
            Packet.decode(bytes)
        } catch (e: PacketFormatException) {
            onDecodeError?.invoke(e)
            return
        }

        if (packet.messageType == MessageType.ACK) {
            retransmissionQueue.acknowledge(packet.messageId)
            deliveryTracker.set(packet.messageId, DeliveryStatus.ACKNOWLEDGED)
            return
        }

        if (!replayWindow.accept(packet.sequenceNumber)) {
            return // duplicate or stale fragment: correct protocol behavior, not an error
        }

        val reassembled = reassembler.onFragment(packet, nowMs) ?: return // message not complete yet

        sendAck(reassembled.sessionId, reassembled.messageId)

        val lang = SemanticPacketWireCodec.langFor(reassembled.langCode)
        val priority = if (reassembled.isAlert) PacketPriority.ALERT_BASE else PacketPriority.NORMAL_BASE
        val semanticPacket = try {
            SemanticPacketWireCodec.decodePayload(reassembled.payload, lang, priority)
        } catch (e: PacketFormatException) {
            onDecodeError?.invoke(e)
            return
        }

        val utterance = codec.decode(semanticPacket)
        val analysis = emergencyAnalyzer.analyze(utterance.text, lang)
        onMessageReceived?.invoke(utterance, analysis)
    }

    private fun sendAck(sessionIdOfMessage: Int, messageId: Int) {
        val seq = nextSequenceNumber
        nextSequenceNumber = (nextSequenceNumber + 1) and 0xFFFF
        val ackPacket = Packet(
            flags = 0,
            sessionId = sessionIdOfMessage,
            senderShortId = senderShortId,
            messageId = messageId,
            sequenceNumber = seq,
            timestampMs = clock() - sessionStartMs,
            langCode = 0,
            messageType = MessageType.ACK,
            fragmentIndex = 0,
            fragmentCount = 1,
            payload = ByteArray(0)
        )
        transport.send(ackPacket.encode())
    }
}
