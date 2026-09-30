package com.itantra.wifi

import com.itantra.core.ThermalState
import com.itantra.core.TransportKind
import com.itantra.protocol.PacketTransport
import com.itantra.protocol.TransportLinkStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Adapter resolving the interface mismatch documented in `docs/KNOWN_ISSUES.md` (2026-09-24
 * entry, gap #1): `orchestrator.MessageOrchestrator` depends on `protocol.PacketTransport`
 * (`send(frame: ByteArray)` — one already-fragmented, already-encoded [com.itantra.protocol.Packet])
 * and reassembles inbound fragments itself; `WifiUdpTransport`'s original public API
 * (`send(payload, langCode, messageType, isAlert)`) instead takes a whole unframed application
 * payload and fragments/reassembles it internally. The two cannot be wired together directly
 * without either double-fragmenting outbound traffic or double-reassembling inbound traffic.
 *
 * This class is the adapter, not a rewrite of [WifiUdpTransport]: it deliberately does NOT use
 * [WifiUdpTransport.send]/[WifiUdpTransport.incomingMessages]/[WifiUdpTransport.receiveLoop]
 * (all three of which fragment/reassemble internally) and instead uses the lower-level,
 * single-packet primitives — [WifiUdpTransport.sendFrame] (added alongside this file, see its
 * KDoc) for outbound, and [WifiUdpTransport.receiveOnce] (already existed, always returned raw
 * per-fragment [com.itantra.protocol.Packet]s) driven in its own loop for inbound — so that
 * [MessageOrchestrator][com.itantra.orchestrator.MessageOrchestrator]'s own
 * `PacketFragmenter`/`FragmentReassembler` remain the single place fragmentation/reassembly
 * happens for traffic that goes through the orchestrator. A caller that wants
 * [WifiUdpTransport]'s own built-in fragmentation (e.g. for a standalone, non-orchestrator use
 * of Wi-Fi transport) should keep using [WifiUdpTransport] directly instead of this adapter —
 * the two are alternative entry points onto the same socket, not both meant to run at once on
 * the same [WifiUdpTransport] instance.
 *
 * `receiveOnce()` blocks its calling thread, so this adapter owns a [CoroutineScope] on
 * [Dispatchers.IO] to drive the receive loop; [close] cancels it. The scope is created lazily,
 * on the first [onReceive] registration, so a sender-only use of this adapter (no inbound
 * callback ever registered) never spins up a receive thread it doesn't need.
 *
 * HONEST SCOPE, same caveat as [WifiUdpTransport] and every other file in this module: this
 * compiles against plain `java.net`/`kotlinx.coroutines` APIs with no Android dependency, but
 * has not been recompiled or run in this sandbox (`kotlinx-coroutines-core` needs Maven
 * Central, off the network allow-list — see `docs/KNOWN_ISSUES.md`). Verify with
 * `./gradlew :wifi:compileDebugKotlin` and, ideally, a real two-process/two-device loopback test
 * through [com.itantra.orchestrator.MessageOrchestrator] before relying on it.
 */
class WifiPacketTransportAdapter(
    private val transport: WifiUdpTransport
) : PacketTransport {

    override val kind: TransportKind = TransportKind.WIFI

    private var receiveJob: Job? = null
    private val receiveScope by lazy { CoroutineScope(Dispatchers.IO) }
    private var activeCallback: ((ByteArray) -> Unit)? = null

    override fun send(frame: ByteArray) {
        transport.sendFrame(frame)
    }

    /**
     * Registers [callback] and starts (once) a background loop that blocks on
     * [WifiUdpTransport.receiveOnce], re-encodes each successfully-decoded, CRC-validated
     * [com.itantra.protocol.Packet] back to wire bytes via `Packet.encode()`, and hands those
     * bytes to [callback] — matching [PacketTransport.onReceive]'s documented contract of "one
     * callback invocation per successfully CRC-validated inbound frame" (a single fragment, not
     * a reassembled message; reassembly is [MessageOrchestrator][com.itantra.orchestrator.MessageOrchestrator]'s
     * job). Calling this a second time replaces the callback but does not start a second loop.
     * Malformed/corrupt datagrams are already dropped inside `receiveOnce()` (§30 fail-closed
     * parsing) and never reach [callback].
     */
    override fun onReceive(callback: (ByteArray) -> Unit) {
        if (receiveJob != null) {
            // A second registration only swaps which callback future frames go to; it does not
            // spin up a second competing reader on the same socket.
            activeCallback = callback
            return
        }
        activeCallback = callback
        receiveJob = receiveScope.launch {
            while (transport.state != WifiConnectionState.DISCONNECTED) {
                val packet = transport.receiveOnce() ?: continue
                activeCallback?.invoke(packet.encode())
            }
        }
    }

    override fun currentStats(): TransportLinkStats {
        // thermalState/batteryPct are not this adapter's concern (they come from
        // android-app's power/thermal monitoring, not the transport) — reuse
        // WifiUdpTransport's own channel-stats accounting with neutral placeholders for the
        // two fields this narrower interface doesn't ask for at all.
        val stats = transport.currentChannelStats(
            thermalState = ThermalState.NOMINAL,
            batteryPct = null
        )
        return TransportLinkStats(
            rttMs = stats.rttMs.toDouble().takeIf { transport.state == WifiConnectionState.CONNECTED },
            rssiDbm = stats.rssiDbm,
            mtuBytes = transport.configuredMtu,
            connected = transport.state == WifiConnectionState.CONNECTED
        )
    }

    override fun close() {
        receiveJob?.cancel()
        receiveJob = null
        transport.close()
    }
}
