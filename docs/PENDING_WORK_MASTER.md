# PENDING WORK MASTER — iTantra-Master

Priorities follow the master integration prompt's scheme: P0 blocks the build, P1 blocks
the first single-device demo, P2 blocks two-device communication, P3 blocks multilingual
support, P4 is performance/reliability, P5 is advanced research. Within each priority,
items are roughly in the order they unblock each other.

---

## P0 — Blocks the build

### P0.1 — Run `./gradlew build` in a networked environment with the Android SDK
**Status**: not done. **Dependency**: none — this repository is ready for it.
**Why it's not done here**: this integration's sandbox has network egress limited to a
small allow-list (npm/pypi/crates/GitHub registries and code/release hosts) that does not
include Google's Maven repo, Maven Central, or the Gradle distribution server, and has no
Android SDK installed. Re-confirmed directly again in the 2026-09-23 session (all four
hosts still return HTTP 403 `host_not_allowed`). See `docs/KNOWN_ISSUES.md`.
**What was done instead (updated 2026-09-23)**: earlier sessions compiled with a
substitute ancient (apt) Kotlin 1.3.31 compiler against a manually-patched scratch copy.
The 2026-09-23 session found that **GitHub release assets are reachable** even though
Maven/Gradle infra isn't, downloaded the real `kotlinc-jvm 2.0.20` distribution directly
from `github.com/JetBrains/kotlin/releases`, and recompiled all 34 main-source files
across the 9 pure-Kotlin/JVM modules **unmodified** — 0 errors, 0 warnings, 102 classes.
This is materially stronger evidence than the prior 1.3.31-substitute result (real target
compiler version, no source patching) and supersedes it; see
`tests/build-logs/2026-09-23_real_kotlinc_2.0.20_main_compile.txt`. JUnit test execution
is still not covered (junit-jupiter jars are also Maven-Central-only; not pursued this
session — low priority relative to main-source verification).
**Validation criteria**: `./gradlew build` exits 0; `./gradlew test` exits 0 for every
module; `./gradlew :android-app:assembleDebug` produces an APK.
**Next command**: `./gradlew build` from the repository root, once run somewhere with
Android SDK + Maven access (a real CI runner, or a developer machine).

### P0.2 — Compile and run `wifi`'s test suite for real
**Status**: written, not executed. **Dependency**: Maven Central access for
`kotlinx-coroutines-core`/`-test` (unavailable in this sandbox).
**Files**: `wifi/src/test/kotlin/com/itantra/wifi/WifiUdpTransportTest.kt` — a real
127.0.0.1 UDP loopback test (single-fragment and multi-fragment cases), analogous to
`protocol/validation/wifi_loopback_reference.py` (which *was* executed and passed).
**Validation criteria**: `./gradlew :wifi:test` passes both cases.

### P0.3 — Verify the Android-SDK-dependent modules compile against the real SDK
**Status**: not done in this sandbox (no SDK here). **Dependency**: P0.1's environment.
**Modules**: `android-app`, `audio`, `alert-engine`.
**Validation criteria**: those three modules compile under the real `com.android.library`/
`com.android.application` Gradle plugins with `compileSdk = 35`, `minSdk = 26`.

---

## P1 — Blocks the first single-device demo (mic → VAD → STT → text → TTS → speaker)

### P1.1 — Real `AudioCapture` implementation (`AudioRecord`/AAudio)
**Status, updated 2026-09-27: WRITTEN, NOT COMPILED, NOT DEVICE-TESTED.**
`AndroidAudioCapture` (`audio/src/main/kotlin/com/itantra/audio/AndroidAudioCapture.kt`) is a
real `AudioRecord`-backed implementation: 16kHz mono, frames sized to match
`vad.EndpointerConfig`'s default 30ms, own dedicated capture thread, no per-frame heap
allocation beyond the fixed read buffer. **Not currently on `SessionService`'s only working
recognition path** — see P1.3 below and `docs/DECISIONS.md` Entry #7 for why; it is wired in
and ready for a future frame-fed `SttEngine`. **Dependency for verification**: P0.3 (Android
SDK) — unchanged, still not available in this sandbox.
**Validation criteria** (unchanged): on a physical device, `start()` delivers real 16kHz mono
`AudioFrame`s at the expected cadence; `stop()` releases the microphone cleanly; a unit
test using a recorded WAV fixture confirms frame timing/format.

### P1.2 — Real `AudioPlayback` implementation (`AudioTrack`)
**Status, updated 2026-09-27: WRITTEN, NOT COMPILED, NOT DEVICE-TESTED.**
`AndroidAudioPlayback` (same file location pattern as P1.1) is a real `AudioTrack`-backed
implementation, wired into `SessionService` for both normal and alert-priority playback (the
latter via `AndroidAlertEngine`). **Dependency for verification**: P0.3, unchanged.
**Validation criteria** (unchanged): `play()` on a physical device produces
audible output from a `SynthesizedAudio`; alert-priority audio-focus behavior matches
§28's DND/call matrix (see `alert-engine`).

### P1.3 — One real offline STT engine, wired behind the canonical `SttEngine` interface
**Status, updated 2026-09-27: a real, working recognition backend now exists
(`AndroidOnDeviceSpeechRecognizer`, `android-app/.../stt/`), but it deliberately does NOT
implement the `stt.SttEngine` interface this item names — see `docs/DECISIONS.md` Entry #7 for
exactly why (Android's `SpeechRecognizer` owns mic capture itself; `SttEngine.onFrame` assumes
an engine that consumes externally-captured frames; the two are architecturally incompatible,
not just unwired).** This item, read as written ("a class implementing `SttEngine`"), remains
NOT DONE — no raw-PCM-consuming model (IndicConformer/sherpa-onnx/Vosk/etc.) has been
integrated, `AndroidAudioCapture` (P1.1) has no `SttEngine` consuming its frames yet, and
`SimulationBackedSttEngine` remains explicitly non-functional-by-design, unchanged.
What changed in practice: `SessionService`'s push-to-talk path is real and (architecture
permitting) functional today via the new class, just not through this specific interface.
**Dependency**: none technically, but should follow a real Model Scorecard
(`tools/model_scorecard/model_scorecard.py`) before adoption, per every source archive's own
research-methodology requirement. **Complexity**: high. **Candidates already tracked**:
IndicConformer (AI4Bharat), sherpa-onnx, per `core/registry/TenLanguageMatrix.kt`'s seeded
manifests — still all placeholders, none downloaded/verified.
**Validation criteria** (unchanged): a class implementing `SttEngine` produces real recognized
text from real microphone audio on a physical device for at least English; WER measured against
a held-out set and recorded in `docs/BENCHMARKS.md`, not asserted. **Separately, now also
needed**: real-device validation of `AndroidOnDeviceSpeechRecognizer`'s actual recognition
quality/offline behavior, tracked as its own item since it sits outside `SttEngine` entirely.

### P1.4 — One real offline TTS engine, wired behind the canonical `TtsEngine` interface
**Status, updated 2026-09-27: WRITTEN, NOT COMPILED, NOT DEVICE-TESTED — and, unlike P1.3, this
one DOES implement the canonical interface.** `AndroidTextToSpeechEngine`
(`android-app/.../tts/AndroidTextToSpeechEngine.kt`) implements `tts.TtsEngine` using
`android.speech.tts.TextToSpeech`, bridging its async API to `synthesize()`'s synchronous
contract via `synthesizeToFile` + a bounded latch wait, then parsing the resulting WAV back into
PCM with a small hand-written reader. `firstAudioReadyTsMs` is honestly reported as equal to
total synthesis time for this specific (file-based, non-streaming) engine choice — see the
class's own KDoc; this is a real, stated limitation, not a bug being hidden.
**Dependency for verification**: P0.3, unchanged. **Complexity as originally scoped**: high;
turned out lower in practice by using the platform's own TTS rather than integrating a
third-party model, at the cost of the model-identity/license/footprint questions this item
originally asked for being unanswerable (ownership sits with whichever engine/voice the device
has installed — see the class's KDoc "MODEL IDENTITY" section).
**Validation criteria** (unchanged): a class implementing `TtsEngine` produces real, audible
synthesized speech for at least English on a physical device; first-audio and total-synthesis
latency recorded via `TtsLatencyTracker` into `docs/BENCHMARKS.md` — not yet done, needs a
device.

### P1.5 — Wire the real pipeline into `SessionService` / a minimal push-to-talk UI
**Status, updated 2026-09-27: WRITTEN, NOT COMPILED, NOT DEVICE-TESTED.** `SessionService` was
rewritten from the bound-but-empty shell into a real implementation (own `SessionStateMachine`,
constructs a real `MessageOrchestrator` + user-chosen transport, drives
`AndroidOnDeviceSpeechRecognizer` -> send, and receive -> `AndroidTextToSpeechEngine` ->
playback). `MainActivity` was rewritten into a real (programmatic UI, see its own KDoc for why
not XML) push-to-talk screen bound to `SessionService.uiState`. **Dependency for verification**:
P0.3 plus a physical device — unchanged; nothing here has been compiled.
**Validation criteria** (unchanged): the "first functional demo" defined in
the original master prompt — press a button, speak, see recognized text, hear synthesized
speech — works on one physical Android phone, no typed input anywhere in the loop.

---

## P2 — Blocks two-device communication

### P2.1 — Wire `codec` + `protocol` + `wifi` into the session pipeline end-to-end
**Status, updated 2026-09-27: WRITTEN, NOT COMPILED, NOT DEVICE-TESTED.** `SessionService`
(see P1.5) now calls `MessageOrchestrator.sendText()` directly from the recognized-text
callback, which itself owns the `codec`/`protocol` encode+fragment steps and calls
`WifiPacketTransportAdapter.send()` (or the BLE equivalent) — no manual step in between, exactly
as this item's validation criteria describe. **Dependency for verification**: P0.2/P0.3,
unchanged — the `wifi`/`android-app` compile blockers are untouched by this session.
**Validation criteria** (unchanged): on Device A, a `RecognizedUtterance` from real STT flows
through `SemanticCodec.encode()` -> `Packet.encode()` -> `WifiUdpTransport.send()` without a
manual step in between.

### P2.2 — Real two-device UDP test over an actual Wi-Fi network (not loopback)
**Status**: not done — only 127.0.0.1 loopback has been exercised (Python reference,
executed and passed; the Kotlin equivalent written but not executed, see P0.2).
**Dependency**: P0.3, two physical Android devices on the same Wi-Fi network.
**Complexity**: low once P2.1 and P0.3 are done. **Validation criteria**: the exact
"second functional demo" defined in the original master prompt — Phone A speaks, Phone B
hears the synthesized result, over a real Wi-Fi link between two physical devices.

### P2.3 — TCP fallback path
**Status**: `WifiUdpTransport.shouldFallBackToTcp()` exists (comparison against
`[TARGET]` thresholds) but nothing implements the actual TCP fallback connection/transfer.
**Dependency**: P2.2 (need real loss/RTT data to pick real thresholds, not guessed ones —
see P4.1). **Complexity**: medium.

### P2.4 — Reconnect state machine for `WifiUdpTransport`
**Status**: not started — `WifiConnectionState` has a `RECONNECTING` value but no code
drives a transition into or out of it. **Dependency**: P2.2. **Complexity**: medium.

### P2.5 — HEMIM: emergency envelope, priority-queue integration, Android UI
**Status, updated 2026-09-24**: (a) and (b) are now DONE — see `docs/ORCHESTRATOR.md` and
`orchestrator/validation/RESULTS.md`. `EmergencyMessageEnvelope` exists
(`orchestrator/.../EmergencyMessageEnvelope.kt`) and `EmergencyPriority` is mapped onto the
real `RetransmissionQueue`/`ReliabilityLayer.kt` via `PriorityMapping` + `MessageOrchestrator`,
real-executed with 37/37 checks passing including queue/ACK/retry/expiry behavior.
**CORRECTION to this entry's earlier text**: it previously said "no class named
`RetransmissionQueue` exists yet, only that file [`ReliabilityLayer.kt`]" — that was wrong;
`RetransmissionQueue` is a real, complete, already-tested class defined in that file (see
`ReliabilityLayer.kt`). The correct earlier gap was simply that nothing had wired HEMIM's
`EmergencyPriority` onto it yet, which is what this item's (b) did.
**Remaining from this item**: (c) a minimal Android emergency UI — still not started, still
needs an Android SDK to compile/verify, which this sandbox does not have.

### P2.6 — Adapt `WifiUdpTransport` to the `PacketTransport` interface
**Status, updated 2026-09-26: IMPLEMENTED, not compiled.** Went with option (2) from this
item's original text (the "cleaner long-term shape") — `WifiUdpTransport` gained a new
`sendFrame(frame: ByteArray)` (one already-encoded packet, one UDP datagram, no internal
fragmentation), and `wifi/src/main/kotlin/com/itantra/wifi/WifiPacketTransportAdapter.kt` is
the new adapter class implementing `PacketTransport` on top of it plus the pre-existing
`receiveOnce()`. `MessageOrchestrator`'s own `PacketFragmenter`/`FragmentReassembler` are now
the only fragmentation/reassembly path for Wi-Fi traffic that goes through it — no logic
duplication added. **Not compiled** — same pre-existing `kotlinx-coroutines`/Maven Central
blocker as the rest of `wifi`; verify with `./gradlew :wifi:compileDebugKotlin` before relying
on it. See `docs/KNOWN_ISSUES.md`'s 2026-09-26 entry for the full writeup.
**Remaining from this item**: none at the design level — P2.1 (below) is now unblocked at the
`wifi` side specifically; still blocked overall on P0.3 (Android SDK) and P1.1-P1.5 (real
audio/STT/TTS) for an actual end-to-end demo.

### P2.7 — Real BLE GATT client/server implementation (§11)
**Status, new 2026-09-26: IMPLEMENTED, not compiled, not run against hardware.**
`android-app/src/main/kotlin/com/itantra/app/ble/` now has `ItantraGattProfile.kt` (service/
characteristic UUIDs and wire-format decisions), `BleGattServerTransport.kt` (peripheral/
server role — advertises, hosts `BluetoothGattServer`, accepts writes, sends notifications),
`BleGattClientTransport.kt` (central/client role — scans, connects, negotiates MTU,
subscribes to notifications, writes characteristics), and `BlePermissions.kt` (API-version-
aware permission-check helper). Both transport classes implement `protocol.PacketTransport`
directly and delegate MTU/fragmentation/ack math to the existing `bluetooth.BleGattTransport`
rather than duplicating it. AndroidManifest.xml gained the pre-Android-12 legacy Bluetooth/
location permissions that were previously missing despite `minSdk = 26` needing them.
**Dependency for verification**: Android SDK (P0.3) to compile at all, then two physical BLE-
capable devices to exercise any of it for real — GATT callback ordering and MTU-negotiation
behavior are genuinely device/OEM-dependent and cannot be responsibly asserted correct from
source reading alone, per `bluetooth.BleGattTransport`'s own original KDoc reasoning (this
item deliberately did NOT invent GATT behavior that isn't documented in the public Android
API — see `docs/KNOWN_ISSUES.md`'s entry for exactly which pieces are flagged
`TODO(needs a physical device)` as most likely to need adjustment: the reconnect/backoff
state machine, deliberately not attempted for the same reason, and the API-33+ non-deprecated
characteristic-value callback overloads, deferred to keep the `minSdk = 26` floor).
**Complexity remaining**: low for compilation verification once P0.3 is available; unknown
until device testing for the two `TODO` items above.

### P2.8 — Wire `BleGattServerTransport`/`BleGattClientTransport` into `SessionService` and a Device Connection screen
**Status, updated 2026-09-27: WRITTEN, NOT COMPILED, NOT DEVICE-TESTED.** `SessionService`
constructs `BleGattClientTransport`/`BleGattServerTransport` directly based on the user's
`TransportSelection` (central = scan, peripheral = advertise — the user's own choice, per
`BleGattClientTransport`'s KDoc, made via `MainActivity`'s transport radio group). The Device
Connection screen's specific job — surfacing discovered peers so the user can pick one, not
auto-connecting to the first result — is implemented in `MainActivity` as a live `ListView`
driven by `SessionUiState.discoveredBleDevices`, tapping an entry calls
`SessionService.connectToBleDevice(address)`. **Dependency for verification**: P2.7 (done),
P0.3 plus two physical BLE-capable devices — unchanged, nothing here has been compiled.
**Complexity remaining**: none at the design/wiring level; all remaining complexity is
verification (compile, then real hardware) rather than more code to write.

---

## P3 — Blocks multilingual support

### P3.1 — Real translation / cross-language semantic transfer
**Status**: NOT IMPLEMENTED, and — per the original master prompt's explicit warning —
**must not be confused with what this codebase already has**. `LanguageRouter` and
`LanguageRegistry` are language *routing/selection* (which language's STT/TTS model to
use), not translation. There is no component anywhere in the merged codebase that takes
text in one language and produces semantically-equivalent text in a different language.
If Device A speaks Hindi and Device B is configured for Tamil, nothing in this repository
today would produce Tamil output — TTS would need Tamil-language text, and nothing
generates that from Hindi input.
**Dependency**: P1.3, P1.4 for at least two languages. **Complexity**: very high — this is
arguably the single largest remaining gap between the current codebase and the project's
stated cross-language communication goal.
**Validation criteria**: the exact "third functional demo" from the original master
prompt — Device A speaks English, Device B (configured for Tamil) produces genuinely
Tamil output, not routed/mistranslated English.

### P3.2 — Second and third real language packs (beyond English)
**Status**: registry entries exist (`TenLanguageMatrix`), all `NOT_STARTED`.
**Dependency**: P1.3, P1.4 per language. **Complexity**: high, ×9 languages.
**Validation criteria**: per `core/registry/LanguagePackManifest.kt`'s `ValidationStatus`
enum — a language only becomes `VALIDATED` after `wer`/`cer`/`alertKeywordRecall` are
actually measured and filled in, never left null and asserted anyway.

### P3.3 — Language switching under load
**Status**: `LanguageRegistry.loadLanguage()` enforces the one-resident-language
invariant (tested, see `LanguageRegistryTest.kt`), but nothing exercises it against real
model load/unload timing yet. **Dependency**: P3.2 (need ≥2 real language packs).

---

## P4 — Performance and reliability

### P4.1 — Measure real loss/RTT/thermal data and re-tune the `[TARGET]` constants
**Status**: every threshold in `codec/ChannelController.kt` (`AdaptiveChannelController`)
and `wifi/WifiUdpTransport.kt` (`WifiTransportConfig`) is explicitly marked `[TARGET]`, a
starting envelope, not a measured value. **Dependency**: P2.2 (need real link data).
**Complexity**: medium, mostly time/access to real devices and varied network conditions.

### P4.2 — BLE: real `BluetoothGatt`/`BluetoothGattCallback` wiring
**Status**: `bluetooth/BleTransport.kt` has real, unit-tested MTU/fragmentation/
windowed-ACK logic, but zero lines touch `android.bluetooth.*` — every GATT-callback
integration point is an explicit `TODO`. **Dependency**: P0.3, a real BLE-capable Android
device (GATT callback ordering is notoriously OEM/version-dependent and cannot be guessed
correctly from documentation alone). **Complexity**: high.
**Migration note**: once this lands, `bluetooth`'s `build.gradle.kts` must change from a
plain Kotlin/JVM module back to `com.android.library` — see
`docs/FILE_CONFLICT_MATRIX.md` "bluetooth" section.
**Validation criteria**: the same two-device demo as P2.2, over BLE instead of Wi-Fi.

### P4.3 — Packet-loss test matrix (0/5/10/20% random + burst + reorder + duplication + corruption)
**Status**: not run. `tools/loss_injection/loss_injection.py` exists and has its own unit
test, but has not been run against the real `protocol`/`wifi` stack end-to-end.
**Dependency**: P2.2. **Complexity**: medium.
**Validation criteria**: `AdaptiveChannelController`'s hysteresis behavior and
`ReliabilityLayer`'s retry/ACK behavior are exercised and measured against this matrix,
and P4.1's thresholds are re-tuned from the results, not left at their placeholder values.

### P4.4 — Battery, thermal, and memory profiling on a real Class L reference device
**Status**: not started — no physical device has been used anywhere in this integration.
**Dependency**: P1.5 at minimum, ideally P2.2/P3.2 for a realistic sustained-use profile.

### P4.5 — Real goodput accounting in `WifiUdpTransport.currentChannelStats()`
**Status**: DONE (2026-09-23 session). `goodputBps` now comes from a real 5-second
sliding-window accountant (`GoodputSample`/`pruneGoodputWindow`/`currentGoodputBps`) fed
by [receiveLoop] on every fully-reassembled message, in `wifi/src/main/kotlin/com/itantra/wifi/WifiUdpTransport.kt`.
**Verification**: the arithmetic was copied verbatim into a coroutine-free harness
(`wifi/validation/GoodputSmokeTest.kt`, since the real class still can't compile here —
see P0.2) and run with the real Kotlin 2.0.20 compiler: 6/6 checks passed, including
empty-window, single-sample, steady-rate, window-pruning, and zero-byte-payload cases —
see `wifi/validation/goodput_smoketest_output.txt`. **What this does not prove**: it has
never measured goodput over a real socket or a real Wi-Fi link, only the isolated
sliding-window math; that remains gated on P0.2/P2.2 like the rest of `wifi`.

---

## P5 — Advanced research features

### P5.1 — Neural VAD
**Status**: `vad/NeuralVadPort.kt` is an honest interface + stub, per the original master
prompt's explicit instruction not to claim this as done. No model, no inference code, no
benchmark. **Dependency**: none blocking; lowest priority per the original prompt's own
ordering ("do not prioritize neural residual... do not block the entire project on a
neural model").

### P5.2 — Neural residual (high-fidelity audio reconstruction alongside the semantic packet)
**Status**: `codec/NeuralResidual.kt`'s `NoOpNeuralResidual` is the only implementation;
`FeatureFlags.neuralResidualEnabled` is `false`. `AdaptiveChannelController` already
tracks a `HIGH_FIDELITY_RESIDUAL` channel mode for when this exists, but nothing produces
or consumes residual bytes today. **Dependency**: P3.2 (need real per-language audio data
to train against). **Complexity**: very high.

### P5.3 — Automatic language identification as the primary (not fallback) language-selection path
**Status**: `LanguageRouter` already supports LID as an optional, confidence-gated
override of explicit selection (tested). Making it the *primary* path, rather than a
fallback behind explicit user selection, is explicitly deprioritized by the original
master prompt ("do not make automatic language detection mandatory for the first
end-to-end demo") and remains so here.

### P5.4 — Advanced entropy-coded / neural-compressed semantic packets beyond BPE
**Status**: `codec/EntropyCoder.kt` (`VarintCodec`, `HuffmanCoder`) exists and is
unit-tested but not yet wired into `BpeSemanticCodec`'s actual wire encoding — token IDs
are currently written as plain `IntArray`, not entropy-coded. **Dependency**: P3.2 (a real
per-language corpus would make entropy-coding gains measurable and meaningful, vs. the
current small placeholder corpus). **Complexity**: medium.
