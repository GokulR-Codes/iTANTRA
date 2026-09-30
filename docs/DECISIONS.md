# DECISIONS.md

A running log of decisions that shape the codebase, in the order they were
made. Each entry is append-only — if a later decision reverses an earlier
one, add a new entry that says so; do not edit history. Entries record what
was decided and why, not a full design doc (that belongs in ARCHITECTURE.md
or an experiments/ file if it needs more room).

---

## Entry #1 — 2026-09-07 — Ingestion of the master development prompt

**Decision**: Adopted `iTantra_SIH26173_Master_Development_Prompt.md` as the
governing specification for this repository. Repository structure, module
boundaries, contracts, and every `§N` reference throughout the codebase's
comments point back to sections of that document.

**Class L target device** (§29, the design floor every module must work
within): 2–4 GB RAM, CPU-only fallback (no NPU/GPU delegate assumed),
`arm64-v8a` primary ABI, running with exactly one active language pack
resident at a time. Class M (4–6 GB RAM, optional NNAPI/GPU delegate) is the
target profile; Class H is a high-end device used only for
training/benchmarking and must never be allowed to hide a Class L failure.

**Rationale**: The document's own closing instruction is explicit: initialize
the repository per §15, log ingestion here with today's date and a one-line
Class L summary, then begin Gate 1 — not produce another planning document.
This entry is that log.

**Note on provenance**: an earlier file in this conversation
(`iTantra_winning_blueprint.md`) was uploaded first and turned out to be the
wrong document — a similar but distinct architecture blueprint for the same
problem statement. It was not used as a source for this repository. Only
`iTantra_SIH26173_Master_Development_Prompt.md` (confirmed by the user as
the correct file) governs what follows.

---

## Entry #2 — 2026-09-07 — CRC placement resolves the blueprint's 24-vs-26-byte header ambiguity

**Decision**: `protocol/`'s wire format is header (24 bytes, fixed) +
payload (variable) + CRC-16 trailer (2 bytes), appended *after* the payload,
not counted inside the 24-byte header.

**Rationale**: §24's field table sums to 26 bytes while its prose states the
header is 24 bytes. Treating CRC-16 as a trailer over header+payload (a
standard framing pattern) reconciles both statements without dropping any
field, and is implemented and fuzz-tested (`protocol/src/test/kotlin/.../PacketCodecTest.kt`,
plus a 20,000-iteration random-input fuzz pass run manually — see
`docs/KNOWN_ISSUES.md` for why that fuzz run isn't yet a CI-executed test).

**Alternative considered**: Shrinking a header field to force 24 bytes
inclusive of CRC. Rejected — no field in §24's table is a plausible
candidate to shrink without losing information (e.g. session/sender IDs
need their full width to avoid collisions across a room full of devices).

---

## Entry #3 — 2026-09-07 — `entries` / `data object` used despite local toolchain not supporting them

**Decision**: Source code targets Kotlin 2.0.20 (per `build.gradle.kts`)
and uses modern idioms (`enum.entries`, `data object`) where they're the
correct choice, even though this authoring sandbox's `apt`-installed
`kotlinc` is version 1.3.31 and cannot compile those constructs.

**Rationale**: The sandbox's ancient local toolchain is an artifact of this
session's network restrictions (no reachable Maven Central / Gradle
distribution server to install a modern toolchain), not a constraint of the
actual target platform. Writing worse Kotlin to satisfy a throwaway local
compiler would be optimizing for the wrong thing. See
`docs/KNOWN_ISSUES.md` for exactly what was and wasn't verified locally as
a result.

---

## Entry #4 — 2026-09-07 — Pure-Kotlin/JVM modules for everything not genuinely Android-specific

**Decision**: `core`, `protocol`, `vad`, `codec`, `language-id`, `stt`,
`tts`, and `benchmark` are plain Kotlin/JVM Gradle modules (no Android
dependency), even though several of them will eventually back Android
features. `audio`, `alert-engine`, `wifi`, `bluetooth`, and `android-app`
are Android modules because they call framework APIs
(`AudioRecord`/`AudioTrack`, `BluetoothGatt`, `WifiP2pManager`,
`NotificationManager`) that have no non-Android equivalent.

**Rationale**: Everything that's pure logic (framing, CRC, energy-VAD math,
codec encode/decode, telemetry schema) should be testable on a plain JVM in
CI without an emulator (§38's stated goal), and should be developable
without waiting on the Android SDK to be available. This split is also why
this authoring sandbox — which has no Android SDK — could still
functionally verify four modules' worth of real logic today instead of
writing everything blind. See `docs/KNOWN_ISSUES.md`.

---

## Entry #5 — 2026-09-26 — BLE central/peripheral role is a user choice, not auto-negotiated

**Decision**: `BleGattClientTransport` (central/scan) and `BleGattServerTransport`
(peripheral/advertise) are both implemented as complete, independent `PacketTransport`s, with
no automatic arbitration between them. Which role a given device plays on a given connection
is decided by which affordance the user taps on §5's Device Connection screen ("scan for a
peer" vs. "wait to be found").

**Rationale**: Two Android devices both defaulting to central (both scan, neither advertises)
or both defaulting to peripheral (both advertise, neither scans) both deadlock — one device has
to advertise and one has to connect. A protocol-level auto-negotiation for this (e.g. a
lowest-MAC-wins rule) is possible in principle but adds a whole new pre-connection discovery
sub-protocol to design, implement, and validate against real hardware, for a two-user-facing-
devices app where a simple UI choice ("Find a nearby phone" / "Let a nearby phone find me")
solves the same problem with zero additional protocol surface. Deferred to the UI layer
deliberately, not left unresolved by omission — see `docs/PENDING_WORK_MASTER.md` P2.8.

**Consequence**: §5's Device Connection screen is now on the critical path for BLE to be
usable at all (not just "nice to have UI around already-working BLE") — it is where the
central/peripheral choice actually gets made, not just displayed.

---

## Entry #6 — 2026-09-26 — Wi-Fi/BLE `PacketTransport` adapters bypass each transport's own high-level fragmentation

**Decision**: `WifiPacketTransportAdapter` calls `WifiUdpTransport.sendFrame()`/`receiveOnce()`
(single already-encoded packet in, single raw fragment out) rather than
`WifiUdpTransport.send()`/`incomingMessages` (whole unframed payload in, whole reassembled
message out). `BleGattServerTransport`/`BleGattClientTransport` similarly hand raw per-fragment
bytes straight to/from GATT, with no reassembly step of their own.

**Rationale**: `orchestrator.MessageOrchestrator` already owns fragmentation
(`protocol.PacketFragmenter`) and reassembly (`protocol.FragmentReassembler`,
`protocol.ReplayWindow`) for any traffic that goes through it. Having a transport
*also* fragment/reassemble underneath the orchestrator would either double-fragment outbound
messages (corrupting them) or silently duplicate reassembly state in two places that could
disagree. Each transport's own higher-level, self-contained fragmentation API
(`WifiUdpTransport.send(payload, ...)`, `bluetooth.BleFragmenter`) remains available and
correct for a caller that bypasses the orchestrator entirely — the two are alternative entry
points at different abstraction levels, not layered on top of each other.

---

## Entry #7 — 2026-09-27 — Android's on-device `SpeechRecognizer` is wired in directly, not behind `stt.SttEngine`

**Decision**: `android-app/.../stt/AndroidOnDeviceSpeechRecognizer.kt` is a standalone class
with its own `start`/`stop`/`cancel`/`destroy` contract. It does **not** implement
`stt.SttEngine`, even though it is this repository's first real, working speech-recognition
backend.

**Rationale**: `SttEngine.onFrame(frame: AudioFrame): RecognizedUtterance?` models an engine
that *consumes* externally-captured 16kHz PCM frames, sitting downstream of
`AudioCapture`/`EnergyGateVad`/`Endpointer` in the pipeline the master prompt's §14 dependency
graph describes. Android's `SpeechRecognizer` is architecturally the opposite: it owns
microphone capture internally (via `startListening(Intent)`) and only ever returns
whole-utterance text through `RecognitionListener` callbacks — there is no public API to feed
it frames captured elsewhere. Two ways to force it behind `SttEngine` were considered and
rejected:
1. Implement `onFrame` as a no-op that ignores its argument and secretly starts the recognizer's
   own capture on the side — technically compiles, but silently lies about what the method does
   to every caller that reads `SttEngine`'s contract.
2. Run `AndroidAudioCapture` (feeding real frames to satisfy the interface honestly) *and* let
   `SpeechRecognizer` capture independently — two concurrent `AudioRecord`-class inputs on one
   microphone, which fails outright or returns silent/corrupted audio on most Android
   implementations (the OS grants exclusive-ish access to one active recording client in
   practice, with device-specific exceptions this project has no hardware to characterize).

Neither is acceptable per this project's "no fake implementations" rule — option 1 is a fake
implementation of the interface itself, and option 2 is a real bug shipped to satisfy a type
signature.

**Consequence**: `SessionService` treats "capture-and-recognize" as one of two mutually
exclusive backends — the self-capturing `AndroidOnDeviceSpeechRecognizer` (real, working today,
the only backend actually wired into the app) or a future frame-fed `SttEngine` implementation
driven by `AndroidAudioCapture` (interface and capture code both real and ready, no engine
behind them yet — see `docs/PENDING_WORK_MASTER.md` P1.3). `stt.SttEngine`'s contract is
unchanged by this decision — a future raw-PCM model (Vosk, sherpa-onnx, etc.) still implements
it exactly as specified, with zero changes needed to the interface itself.

---
