# :orchestrator — Validation Results

**Status:** `[MEASURED]` — compiled and executed in this session's sandbox against the real
production classes (not a reimplementation), same methodology as `alert-engine/validation/RESULTS.md`.

**Commands:**
```
kotlinc @core_alert-engine_codec_protocol_orchestrator_file_list.txt -d orch_main.jar
kotlinc -cp orch_main.jar orchestrator/validation/OrchestratorSmokeTest.kt -d orch_smoketest.jar
java -cp orch_main.jar:orch_smoketest.jar:kotlin-stdlib.jar OrchestratorSmokeTestKt
```
**Environment:** same sandbox/compiler as `alert-engine/validation/RESULTS.md` (kotlinc-jvm
2.0.20 from GitHub releases, JDK 21, no Android SDK, no Maven Central).
**Date:** 2026-09-24.

## What was actually tested

The real `MessageOrchestrator`, `FakeInMemoryTransport`, `PriorityMapping`,
`DeliveryTracker`, and (via them) the real `RuleBasedEmergencyAnalyzer`,
`Utf8BaselineCodec`, `PacketFragmenter`, `FragmentReassembler`, `RetransmissionQueue`,
`ReplayWindow`, and `SemanticPacketWireCodec` — a complete two-endpoint pipeline, called
directly, not mocked out. Two `FakeInMemoryTransport` instances linked to each other stand
in for a real Wi-Fi/BLE link, exactly as driving-prompt §16.4 sanctions ("use a clearly
labeled local test transport and separately report the missing physical-device
verification" — see "What this does NOT prove" below).

## Result

```
OrchestratorSmokeTest.kt                        — 22/22 checks passed
PacketFragmenterAndWireCodecSmokeTest.kt         — 15/15 checks passed (protocol/validation/)
Total (this session's new pure-Kotlin subsystem) — 37/37 checks passed
```
Combined with HEMIM's 64/64 from the previous session, **101/101 real-executed checks pass**
across the whole pure-Kotlin/JVM stack this repository now has.

Full output: `orchestrator_smoketest_output.txt` (this directory) and
`protocol/validation/packet_fragmenter_wirecodec_smoketest_output.txt`. Compile log:
`tests/build-logs/2026-09-24_orchestrator_full_stack_compile.txt`.

## A real bug was found and fixed while writing this test

The first run of `OrchestratorSmokeTest.kt` failed 2 of 22 checks: single-fragment messages
that received their ACK showed `TRANSMITTING`, not `ACKNOWLEDGED`, right after `sendText()`
returned. Root cause: `FakeInMemoryTransport.send()` is synchronous, so a single-fragment
message's entire round trip — sender sends the fragment, receiver reassembles it and
immediately sends an ACK back, sender receives the ACK and marks `ACKNOWLEDGED` — happens
**inside** the `transport.send()` call at the bottom of `sendText()`, before that function's
own final line unconditionally overwrote the status to `TRANSMITTING`, clobbering the
already-correct `ACKNOWLEDGED`. Fixed by making both `sendText()`'s and `retryPending()`'s
final status writes conditional on not already being `ACKNOWLEDGED` — see the inline
comments at both call sites in `MessageOrchestrator.kt` for the full explanation. This is
exactly the kind of bug a synchronous test transport surfaces immediately and a purely
async, real-network transport might have masked for a long time (the race would only show
up when RTT happens to be near-zero) — worth keeping in mind if `WifiUdpTransport` is ever
made to implement `PacketTransport` directly (see "Known architectural gap" below).

## What this does NOT prove

- **No real network, radio, or Android involved.** `FakeInMemoryTransport` is an honest,
  labeled loopback (see its own KDoc) — this is unit/integration-level evidence, not
  physical-device or even real-socket evidence. Compare to `protocol/validation/
  wifi_loopback_reference.py`, which *did* exercise a real kernel UDP socket for the
  pre-existing `Packet`/CRC/fragmentation logic — that remains the closest thing to network
  evidence in this repository, and it predates and doesn't cover the new orchestrator code.
- **`WifiUdpTransport` does not implement `PacketTransport`.** Discovered while designing
  this module: `WifiUdpTransport.send(payload, langCode, messageType, isAlert)` takes an
  *unframed* application payload and fragments it internally (now via the shared
  `PacketFragmenter`, see the wifi refactor note in `docs/ORCHESTRATOR.md`), whereas
  `PacketTransport.send(frame: ByteArray)` expects an *already-framed*, single packet ready
  to put on the wire as-is — `MessageOrchestrator` calls the latter, once per fragment it has
  already built. These are two different abstraction levels. Wiring `MessageOrchestrator` to
  real Wi-Fi therefore needs either an adapter or a small `WifiUdpTransport` refactor, not
  just "pass it in" — this is real remaining work, not a design deficiency in either class
  taken alone. See `docs/ORCHESTRATOR.md` and `docs/PENDING_WORK_MASTER.md`.
- **No STT/TTS.** `sendText()` takes already-recognized text; nothing in this repository
  performs real offline speech recognition yet (see `docs/HEMIM.md` and the top-level
  handover report for why that remains blocked in this sandbox).
- **`retryPending()`'s retry/backoff policy is a single sweep-and-resend, not a scheduled
  loop.** A real Android session service would call it periodically; nothing in this
  pure-JVM module can own a timer itself without either coroutines or a platform scheduler.
