# `:orchestrator` — Message Orchestration

**Status: `[IMPLEMENTED]` and real-executed, 2026-09-24.** New pure-Kotlin/JVM module
connecting HEMIM (`alert-engine`), semantic encoding (`codec`), and wire framing/reliability
(`protocol`) into one working outgoing/incoming pipeline — the driving prompt's Layer 8
("Connect all modules through stable interfaces... maintain a clear and traceable message
lifecycle") and its explicit P0 items #3-#7 (connect the core pipeline, encoding, framing,
fragmentation/reassembly, transport, message delivery).

## Why a new module

Message orchestration didn't fit any existing module without inverting a dependency
direction: it needs `core` + `alert-engine` (HEMIM) + `codec` + `protocol` together, and
none of those four depend on each other that way today. `docs/ARCHITECTURE.md` already
states the relevant principle for `android-app` — "wires all other modules together but
must stay thin — logic belongs in the owning module, not here" — which implies a home for
this pipeline logic that isn't `android-app` itself. `:orchestrator` is that home. All four
of its dependencies (`core`, `alert-engine`, `codec`, `protocol`) are themselves pure
Kotlin/JVM (none import `android.*`), so this module compiles and unit-tests completely in
this sandbox with no Android SDK — see `orchestrator/validation/RESULTS.md`.

## What it does

**Outgoing** (`MessageOrchestrator.sendText`): HEMIM classify -> map `EmergencyPriority` to
`PacketPriority` (`PriorityMapping`, exactly the mapping `docs/HEMIM.md` named as its own
next step: P0/P1/P2 -> ALERT_BASE, P3/P4 -> NORMAL_BASE, UNKNOWN -> NORMAL_BASE, never
RESIDUAL) -> `SemanticCodec.encode` -> `SemanticPacketWireCodec.encodePayload` (new — see
below) -> `PacketFragmenter.buildFragmentPackets` (new — see below) ->
`RetransmissionQueue.enqueue` -> `PacketTransport.send`.

**Incoming** (wired automatically via `PacketTransport.onReceive`): `Packet.decode` -> ACK
short-circuit (updates `RetransmissionQueue`/`DeliveryTracker`), or `ReplayWindow.accept` ->
`FragmentReassembler` -> on completion, send an ACK back -> `SemanticPacketWireCodec.decodePayload`
-> `SemanticCodec.decode` -> **the receiving device independently re-runs HEMIM on the
reconstructed text** -> delivered to the caller with both the text and the receiver's own
classification.

That last point is a deliberate design decision, not an oversight: the sender's
`EmergencyAnalysis` never crosses the wire at all (only the coarse `Packet.isAlert` bit
does — see `SemanticPacketWireCodec`'s KDoc). HEMIM is deterministic and fully offline, so
re-running it on the receiver costs nothing and means the receiving device's own alerting UI
never has to blindly trust a number the sender computed.

## Two real gaps found and fixed this session

1. **No `SemanticPacket` <-> wire-bytes serialization existed anywhere in the repository.**
   A full grep confirmed it — `android-app/SessionService.kt` was (and still is) an empty
   shell. `protocol.SemanticPacketWireCodec` (new) is that missing piece.
2. **Fragmentation logic was duplicated.** `wifi.WifiUdpTransport.send()` inlined its own
   chunking + `Packet`-building; `bluetooth.BleFragmenter.fragment()` separately did half the
   same job (raw chunking only, no `Packet` building at all). `protocol.PacketFragmenter`
   (new) is the single shared implementation. `WifiUdpTransport.send()` was refactored to
   call it — **reviewed line-by-line, but not recompiled**: `wifi` still cannot be compiled
   in this sandbox at all (`kotlinx-coroutines` needs Maven Central, off the network
   allow-list — the same pre-existing constraint `docs/KNOWN_ISSUES.md` already documented
   for every other line in that file). Verify with `./gradlew :wifi:compileDebugKotlin`
   before relying on it. `BleFragmenter` itself was left untouched this session (not on the
   `MessageOrchestrator` critical path); pointing it at the same shared object is a small,
   clearly-scoped follow-up.

## A real bug found and fixed via testing

The first execution of `orchestrator/validation/OrchestratorSmokeTest.kt` failed 2 of 22
checks: a delivery-status ordering bug where a synchronous transport's instant ACK round
trip got clobbered by an unconditional status write immediately after. Root cause, fix, and
why it matters for a future async transport are documented in
`orchestrator/validation/RESULTS.md` and in `MessageOrchestrator.kt`'s inline comments at
both affected call sites (`sendText`, `retryPending`).

## What's real vs. what's still a gap

| Piece | Status |
|---|---|
| `EmergencyMessageEnvelope`, `PriorityMapping`, `DeliveryStatus`/`DeliveryTracker` | `[IMPLEMENTED]`, real-executed |
| `PacketFragmenter`, `SemanticPacketWireCodec` (in `protocol`) | `[IMPLEMENTED]`, real-executed, including corrupt/truncated-input rejection |
| `MessageOrchestrator` full send/receive/ACK/retry/expire pipeline | `[IMPLEMENTED]`, real-executed end-to-end over a loopback transport, including fragmentation, corruption, duplication, dropped ACKs, and TTL expiration |
| `FakeInMemoryTransport` | `[IMPLEMENTED]` — an honestly-labeled loopback double (see its own KDoc for exactly what it is and isn't) |
| Wiring to a **real** transport (Wi-Fi/BLE) | `[BLOCKED]` — `WifiUdpTransport` doesn't implement `PacketTransport` (see `orchestrator/validation/RESULTS.md` "Known architectural gap"); needs an adapter or a small refactor, and `wifi`/`bluetooth` can't be compiled in this sandbox regardless (coroutines). |
| Wiring to `android-app`/`SessionService` | `[BLOCKED]` — no Android SDK in this sandbox to compile/verify against; see top-level handover report. Deliberately not attempted blind. |
| Real STT feeding `sendText` instead of typed/composed text | `[BLOCKED]` — no offline ASR engine exists in this repository yet (see `docs/HEMIM.md`). |
| Congestion/backoff policy beyond one `retryPending()` sweep | `[NOT IMPLEMENTED]` — needs a real scheduler (Android session service), out of scope for a pure-JVM module. |
| Encryption | `[NOT IMPLEMENTED]` — CRC16 is corruption detection only, not authentication (unchanged from before this session; see `docs/KNOWN_ISSUES.md`). |

## Verification

`orchestrator/validation/RESULTS.md` and `protocol/validation/packet_fragmenter_wirecodec_smoketest_output.txt`
— 37/37 real-executed checks this session, 101/101 combined with HEMIM's prior 64/64.
Matching JUnit5 sources exist at `orchestrator/src/test/kotlin/` and
`protocol/src/test/kotlin/com/itantra/protocol/{PacketFragmenterTest,SemanticPacketWireCodecTest}.kt`
for `./gradlew :orchestrator:test` / `:protocol:test` once Maven access exists — not yet run
by Gradle in this sandbox, same caveat as `alert-engine`'s JUnit files.
