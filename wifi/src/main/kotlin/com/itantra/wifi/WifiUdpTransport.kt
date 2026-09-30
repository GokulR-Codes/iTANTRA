package com.itantra.wifi

import com.itantra.core.ChannelStats
import com.itantra.core.ThermalState
import com.itantra.protocol.FragmentReassembler
import com.itantra.protocol.MessageType
import com.itantra.protocol.Packet
import com.itantra.protocol.PacketFragmenter
import com.itantra.protocol.PacketFormatException
import com.itantra.protocol.ReassembledMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

/**
 * Wi-Fi transport, §25: "Local Wi-Fi Direct or hotspot for discovery/
 * pairing; UDP as the primary transport with a TCP fallback path
 * (decide the fallback trigger from measured loss/RTT, not a guess); an
 * explicit reconnect state machine."
 *
 * FRAGMENTATION SCHEME: identical in design to
 * protocol/validation/wifi_loopback_reference.py, which was EXECUTED
 * against a real kernel UDP socket on 127.0.0.1 (3/3 checks passed —
 * single-fragment, multi-fragment, and empty-payload messages all
 * reassembled byte-exact; see protocol/validation/wifi_loopback_output.txt).
 * This class ports that proven fragmentation scheme onto `DatagramSocket`
 * (plain `java.net`, works identically on this JVM and on Android). What is
 * NOT yet proven: this exact Kotlin class compiled and run on an Android
 * device or emulator, Wi-Fi Direct/hotspot discovery and pairing (no device
 * available to test against — see docs/KNOWN_ISSUES.md), and the
 * UDP-to-TCP fallback trigger (design only, no threshold has been measured
 * against real loss/RTT data yet).
 *
 * INTEGRATION FIX (see docs/FILE_CONFLICT_MATRIX.md row "WifiTransport /
 * FragmentReassembler"): the archive this was ported from declared an
 * `incomingPackets: Flow<Packet>` that nothing ever emitted into, and
 * `receiveOnce()` returned individual fragments with no reassembly —
 * exactly the "Kotlin fragment reassembler likely missing" gap the master
 * integration prompt anticipated. [receiveLoop] and [incomingMessages]
 * below are the fix: they drive the receive loop, feed every fragment
 * through [FragmentReassembler], and emit only complete messages.
 */
enum class WifiConnectionState { DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED, RECONNECTING }

data class WifiTransportConfig(
    val mtu: Int = 1200, // conservative default well under typical Wi-Fi Direct path MTU; §25 says negotiate, not hardcode long-term
    val fallbackLossThresholdPct: Double = 15.0, // [TARGET] — not yet validated against §36's matrix
    val fallbackRttThresholdMs: Double = 600.0, // [TARGET]
    val reconnectBackoffMs: LongArray = longArrayOf(200, 500, 1000, 2000, 5000)
)

/**
 * NOTE: `DatagramSocket` is plain `java.net`, so this class is exercised by this repository's
 * own JVM unit tests (see WifiUdpTransportTest, a real loopback test on 127.0.0.1) — but there
 * is no Android device/emulator in this integration's environment, so the discovery/pairing
 * half of real Wi-Fi Direct has NOT been instantiated or run against real Android APIs.
 */
class WifiUdpTransport(
    private val config: WifiTransportConfig,
    private val sessionId: Int,
    private val senderShortId: Int
) {
    var state: WifiConnectionState = WifiConnectionState.DISCONNECTED
        private set

    /** Configured MTU (§25 default 1200, see [WifiTransportConfig]) — exposed read-only so
     *  adapters (e.g. [WifiPacketTransportAdapter]) can report it without duplicating/guessing
     *  the value themselves. This is the configured ceiling, not a per-link negotiated value —
     *  Wi-Fi (unlike BLE) has no per-connection MTU negotiation step in this design. */
    val configuredMtu: Int get() = config.mtu

    private var socket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = 0
    private var nextSequenceNumber = 0
    private var nextMessageId = 0

    private val reassembler = FragmentReassembler()
    private val _incomingMessages = MutableSharedFlow<ReassembledMessage>(extraBufferCapacity = 64)
    /** Fully reassembled messages, ready for `codec.SemanticCodec.decode()`. Emitted by [receiveLoop]. */
    val incomingMessages: Flow<ReassembledMessage> = _incomingMessages

    private var lastRttMs = 0f
    private var lastLossPct = 0f

    /**
     * Sliding-window goodput accounting (P4.5, PENDING_WORK_MASTER.md).
     * "Goodput" here means the receive-side rate of fully-reassembled application
     * payload bytes actually delivered on [incomingMessages] — not raw wire bytes,
     * which would also count fragment headers/trailers and any retransmitted or
     * duplicate fragments. Only [receiveLoop] feeds this; a caller using
     * [receiveOnce] directly (bypassing reassembly) will not affect it.
     * This is real arithmetic over real samples, but it has only ever been
     * exercised by this session's own unit test on 127.0.0.1 — it has never
     * measured goodput over an actual Wi-Fi link. Do not read the numbers it
     * produces as validated real-world throughput.
     */
    private data class GoodputSample(val atMs: Long, val bytes: Int)
    private val goodputWindow = ArrayDeque<GoodputSample>()
    private val goodputWindowMs = 5_000L

    private fun pruneGoodputWindow(nowMs: Long) {
        while (goodputWindow.isNotEmpty() && nowMs - goodputWindow.first().atMs > goodputWindowMs) {
            goodputWindow.removeFirst()
        }
    }

    private fun recordGoodputSample(bytes: Int, atMs: Long) {
        goodputWindow.addLast(GoodputSample(atMs, bytes))
        pruneGoodputWindow(atMs)
    }

    /**
     * bits/sec of successfully-reassembled payload delivered within the trailing
     * [goodputWindowMs] window. Returns 0f if nothing has been fully reassembled
     * in that window (including: no traffic at all, or every message currently
     * incomplete). Single-sample windows use the window length itself as the
     * elapsed-time denominator (there is no second data point to measure an
     * interval from) rather than dividing by a near-zero elapsed time and
     * producing a misleadingly huge spike.
     */
    private fun currentGoodputBps(nowMs: Long): Float {
        pruneGoodputWindow(nowMs)
        if (goodputWindow.isEmpty()) return 0f
        val totalBytes = goodputWindow.sumOf { it.bytes }
        val oldest = goodputWindow.first().atMs
        val elapsedMs = (nowMs - oldest).takeIf { it > 0 } ?: goodputWindowMs
        return (totalBytes.toDouble() * 8_000.0 / elapsedMs).toFloat()
    }

    /** Opens a local UDP socket and moves to CONNECTING. Actual Wi-Fi Direct/hotspot discovery is out of scope for this class — see class doc. */
    fun open(localPort: Int = 0) {
        state = WifiConnectionState.DISCOVERING
        socket = DatagramSocket(localPort)
        state = WifiConnectionState.CONNECTING
    }

    fun connectTo(address: InetAddress, port: Int) {
        remoteAddress = address
        remotePort = port
        state = WifiConnectionState.CONNECTED
    }

    /**
     * Fragments [payload] per §24/§25 and sends each fragment as one UDP
     * datagram, mirroring the scheme proven in wifi_loopback_reference.py.
     *
     * REFACTORED 2026-09-24: this method used to inline its own chunking +
     * [Packet]-building logic. That logic is now [PacketFragmenter.buildFragmentPackets]
     * (see its KDoc — the same code was duplicated a second time, partially, in
     * `bluetooth.BleFragmenter`; both now have one shared implementation to call). Behavior
     * is unchanged: same MTU accounting, same empty-payload handling, same flag logic, same
     * sequence-number wraparound. CAVEAT, stated plainly: this specific edit has been
     * reviewed line-by-line against the pre-refactor version but has **not** been
     * recompiled — `wifi` still cannot be compiled in this sandbox at all (kotlinx-coroutines
     * needs Maven Central, off the network allow-list — see docs/KNOWN_ISSUES.md), the same
     * pre-existing constraint that already applied to every other line in this file. Verify
     * with `./gradlew :wifi:compileDebugKotlin` before relying on it.
     */
    fun send(payload: ByteArray, langCode: Int, messageType: MessageType, isAlert: Boolean) {
        val sock = socket ?: error("send() called before open()")
        val addr = remoteAddress ?: error("send() called before connectTo()")

        val maxFragmentPayload = PacketFragmenter.maxFragmentPayload(config.mtu)
        require(maxFragmentPayload > 0) { "MTU too small for even an empty fragment: ${config.mtu}" }

        val messageId = nextMessageId
        nextMessageId = (nextMessageId + 1) and 0xFFFF

        val fragmented = PacketFragmenter.buildFragmentPackets(
            payload = payload,
            sessionId = sessionId,
            senderShortId = senderShortId,
            messageId = messageId,
            startSequenceNumber = nextSequenceNumber,
            langCode = langCode,
            messageType = messageType,
            isAlert = isAlert,
            maxFragmentPayload = maxFragmentPayload
            // timestampMs intentionally left at its default (0) — same pre-existing gap as
            // before the refactor, see PacketFragmenter.buildFragmentPackets's KDoc and
            // PacketFormat.kt's WARNING on Packet.timestampMs.
        )
        nextSequenceNumber = fragmented.nextSequenceNumber

        fragmented.packets.forEach { packet ->
            val wire = packet.encode()
            sock.send(DatagramPacket(wire, wire.size, addr, remotePort))
        }
    }

    /**
     * Sends one already-encoded [frame] (e.g. `Packet.encode()`'s output) as a single UDP
     * datagram, with NO internal fragmentation. Added 2026-09-26 for [WifiPacketTransportAdapter]
     * (see docs/KNOWN_ISSUES.md gap #1, 2026-09-24 entry): `orchestrator.MessageOrchestrator`
     * already fragments via `protocol.PacketFragmenter` itself and calls
     * `protocol.PacketTransport.send(frame: ByteArray)` once per already-built [Packet] — calling
     * the existing [send] (which fragments an unframed application payload) from that call site
     * would silently double-fragment every message. This method is the one-frame-per-datagram
     * primitive the adapter needs; the existing [send] is unchanged and still correct for
     * standalone/direct callers (and this session's own test suite) that hand it a whole
     * unframed payload. CAVEAT (same as every other line in this file): written but not
     * recompiled in this sandbox — `wifi` needs `kotlinx-coroutines` from Maven Central, off the
     * network allow-list. Verify with `./gradlew :wifi:compileDebugKotlin` / `:wifi:test`.
     */
    fun sendFrame(frame: ByteArray) {
        val sock = socket ?: error("sendFrame() called before open()")
        val addr = remoteAddress ?: error("sendFrame() called before connectTo()")
        sock.send(DatagramPacket(frame, frame.size, addr, remotePort))
    }

    /** Blocking receive of one wire packet (one fragment); call from a dedicated IO dispatcher/thread,
     *  never the main/audio thread. Returns null on a malformed/corrupted datagram (dropped, per §30
     *  fail-closed parsing) or if the socket was closed while blocked in receive(). Most callers should
     *  prefer [receiveLoop] + [incomingMessages], which handles reassembly; this is exposed for tests
     *  and for callers that want raw per-fragment visibility. */
    fun receiveOnce(bufferSize: Int = 2048): Packet? {
        val sock = socket ?: return null
        val buf = ByteArray(bufferSize)
        val dgram = DatagramPacket(buf, buf.size)
        return try {
            sock.receive(dgram)
            Packet.decode(dgram.data.copyOfRange(0, dgram.length))
        } catch (e: PacketFormatException) {
            null // malformed/corrupted datagram — dropped, per §30 fail-closed parsing
        } catch (e: SocketException) {
            null // socket closed while blocked in receive() — normal on close(), not an error to surface
        }
    }

    /**
     * Drives [receiveOnce] in a loop, feeding every fragment through [FragmentReassembler] and
     * emitting each completed message on [incomingMessages]. Call this from a dedicated IO
     * coroutine dispatcher — it blocks its calling thread inside each `receiveOnce()` call, same
     * rule as `receiveOnce()` itself. Returns (loop exits) once `close()` moves state to
     * DISCONNECTED and the in-flight `receive()` call unblocks with a [SocketException].
     */
    suspend fun receiveLoop() {
        while (state != WifiConnectionState.DISCONNECTED) {
            val packet = receiveOnce() ?: continue
            val nowMs = System.currentTimeMillis()
            val complete = reassembler.onFragment(packet, nowMs = nowMs)
            if (complete != null) {
                recordGoodputSample(complete.payload.size, nowMs)
                _incomingMessages.emit(complete)
            }
        }
    }

    fun currentChannelStats(thermalState: ThermalState, batteryPct: Int?): ChannelStats = ChannelStats(
        rttMs = lastRttMs,
        lossPct = lastLossPct,
        // Real sliding-window accounting over reassembled receive traffic (see goodputWindow
        // above) — no longer a hardcoded placeholder. Still only ever measured on 127.0.0.1
        // in this session's own test, never over a real Wi-Fi link (see class doc).
        goodputBps = currentGoodputBps(System.currentTimeMillis()),
        rssiDbm = null, // Wi-Fi RSSI needs WifiManager, not exercised outside a real Android device
        queueDepth = 0,
        thermalState = thermalState,
        batteryPct = batteryPct
    )

    /**
     * §25: "decide the fallback trigger from measured loss/RTT, not a
     * guess." The comparison against config thresholds is implemented;
     * the thresholds themselves are [TARGET] pending real measurement
     * (see WifiTransportConfig doc). Returns true if the caller should
     * switch to a TCP fallback path (not implemented — see PENDING_WORK_MASTER.md).
     */
    fun shouldFallBackToTcp(): Boolean =
        lastLossPct >= config.fallbackLossThresholdPct || lastRttMs >= config.fallbackRttThresholdMs

    fun close() {
        socket?.close()
        socket = null
        state = WifiConnectionState.DISCONNECTED
    }
}
