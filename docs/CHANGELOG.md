# CHANGELOG.md

Dated log of significant changes. Pre-2026-09-24 history is summarized from
`docs/MASTER_INTEGRATION_AUDIT.md` and `docs/IMPLEMENTATION_STATUS.md`, which remain the
authoritative record of that work — this file does not re-derive detail this session didn't
personally verify.

## 2026-09-27 — Real audio/STT/TTS/alert engines + SessionService/MainActivity wired end-to-end (P1.1-P1.5, P2.1, P2.8)

**New**, all real (standard API usage, not simulated) and all **unverified — never compiled,
this session's sandbox had no Kotlin compiler at all, not even the GitHub-hosted substitute a
prior session used, see `docs/KNOWN_ISSUES.md`'s entry for this session for exactly why**:

- `audio`: `AndroidAudioCapture` (`AudioRecord`), `AndroidAudioPlayback` (`AudioTrack`) —
  P1.1/P1.2.
- `android-app/.../stt/AndroidOnDeviceSpeechRecognizer.kt` — real `android.speech.SpeechRecognizer`
  wiring, P1.3. Deliberately does not implement `stt.SttEngine` — see `docs/DECISIONS.md` Entry #7.
- `android-app/.../tts/AndroidTextToSpeechEngine.kt` — real `android.speech.tts.TextToSpeech`
  implementation of `tts.TtsEngine`, P1.4.
- `android-app/.../alert/AndroidAlertEngine.kt` — real `AudioManager`/`NotificationManager`
  implementation of `alert.AlertEngine`.
- `android-app/.../SessionStateMachine.kt` (+ JUnit5 tests) — new pure-Kotlin/JVM explicit
  session-lifecycle state machine.
- `android-app/.../SessionService.kt` — rewritten from the Gate-1 empty shell into the real
  session orchestrator: wires the above plus the pre-existing `orchestrator.MessageOrchestrator`
  and `wifi`/`bluetooth` `PacketTransport`s into one push-to-talk pipeline. P1.5, P2.1, P2.8.
- `android-app/.../MainActivity.kt` — rewritten from the Gate-1 shell into a real (programmatic,
  not XML-layout) UI: language selection, Wi-Fi/BLE connection controls (including a live BLE
  discovered-device list), push-to-talk, and sent/received/diagnostic status.
- `android-app/build.gradle.kts` — added `kotlinx-coroutines-core`/`-android` 1.8.1 (matching
  `wifi`'s existing pin), `androidx.lifecycle:lifecycle-runtime-ktx:2.8.4`, and a JUnit5 test
  config matching every other module's.

**Explicitly not attempted this session**: real translation (P3.1, unchanged, still not
implemented anywhere in this repository); any compilation or device verification of any kind;
the BLE reconnect/backoff state machine or API 33+ characteristic-value overloads (both still
`TODO(needs a physical device)` from the 2026-09-26 session, untouched); a separate "Model
management" screen (nothing real to manage yet — see `core.registry.TenLanguageMatrix`).

**Docs**: this entry; `docs/KNOWN_ISSUES.md` new top entry; `docs/DECISIONS.md` Entry #7;
`docs/IMPLEMENTATION_STATUS.md` Stages 1/2 updated from NOT STARTED; `docs/PENDING_WORK_MASTER.md`
P1.1-P1.5/P2.1/P2.8 updated; `README.md` status summary updated.

## 2026-09-24 (this session, part 2) — `:orchestrator`: the message pipeline

**New module** `:orchestrator` (pure Kotlin/JVM), wiring HEMIM + `codec` + `protocol` into a
real outgoing/incoming message pipeline (`MessageOrchestrator`, `EmergencyMessageEnvelope`,
`PriorityMapping`, `DeliveryStatus`/`DeliveryTracker`, `FakeInMemoryTransport`).

**New in `protocol`**: `PacketFragmenter` (shared send-side fragmentation, replacing logic
duplicated between `wifi.WifiUdpTransport` and `bluetooth.BleFragmenter`) and
`SemanticPacketWireCodec` (the `SemanticPacket`<->wire-bytes serialization that did not
exist anywhere in the repository before this session).

**Modified**: `wifi/src/main/kotlin/com/itantra/wifi/WifiUdpTransport.kt` — `send()`
refactored to call `PacketFragmenter.buildFragmentPackets` instead of inlining the same
logic. Reviewed line-by-line, behavior-preserving, but **not recompiled** (`wifi` cannot be
compiled in this sandbox — see `docs/KNOWN_ISSUES.md`).

**Bug found and fixed**: a delivery-status ordering bug in `MessageOrchestrator.sendText`/
`retryPending`, found by the first real execution of the new test suite (2 of 22 checks
failing), root-caused and fixed the same session. Full writeup:
`orchestrator/validation/RESULTS.md`.

**Architectural gap found, documented, not yet fixed**: `WifiUdpTransport` does not
implement `protocol.PacketTransport` (two incompatible abstraction levels for "send" — see
`docs/ORCHESTRATOR.md`). Tracked as `docs/PENDING_WORK_MASTER.md` P2.6.

**Verification**: 37/37 real-executed checks (`orchestrator/validation/RESULTS.md`,
`protocol/validation/RESULTS.md`), on top of HEMIM's existing 64/64 — 101/101 combined.
JUnit5 sources added for both new pieces; not yet run by Gradle (no Maven access here).

**Docs**: added `docs/ORCHESTRATOR.md`, `docs/TESTING.md`, this file; updated
`docs/IMPLEMENTATION_STATUS.md` (Stage 8), `docs/PENDING_WORK_MASTER.md` (P2.5 marked done,
new P2.6), `docs/KNOWN_ISSUES.md` (new top section), `README.md`.

**Correction to prior-session documentation**: `docs/PENDING_WORK_MASTER.md` previously
stated "no class named `RetransmissionQueue` exists yet" — incorrect; it's a real, complete,
already-tested class in `protocol/ReliabilityLayer.kt`. Corrected in place, not silently
edited away — see that file's P2.5 entry.

**Deliberately not attempted**: wiring `:orchestrator` into `android-app/SessionService.kt`
(no Android SDK in this sandbox to compile/verify against — editing it unverifiable was
judged worse than leaving the accurate empty-shell state documented); real offline STT/TTS
(no engine exists in this repository, and none is obtainable over this sandbox's network
allow-list); an APK build; any physical-device test. See the session's final handover report
for the complete, itemized accounting.

## 2026-09-24 (this session, part 1) — HEMIM V1-V3

**New**: `alert-engine`'s HEMIM subsystem — rule-based emergency detection
(`RuleBasedEmergencyAnalyzer`, `EmergencyRuleRegistry`), critical-entity extraction
(`RegexCriticalEntityExtractor`), negation/question/historical-framing context handling
(`NegationDetector`, `ContextAnalyzer`), plus supporting data contracts (`EmergencyCategory`,
`EmergencyPriority`, `EmergencySignal`, `CriticalEntity`, `EmergencyAnalysis`),
`EmergencyPolicy`, and `ConfidenceFusion`. English only; HEMIM V4 (ML classifier), V5 (full
evidence fusion), and V6 (multilingual) explicitly out of scope, per the driving prompt's own
"don't start with a complex model before the baseline works" instruction.

**Verification**: 64/64 real-executed checks (`alert-engine/validation/RESULTS.md`),
covering every negation/question/historical adversarial case the driving prompt named
explicitly. JUnit5 sources added; not yet run by Gradle.

**Docs**: added `docs/HEMIM.md`; updated `docs/IMPLEMENTATION_STATUS.md` (Stage 7),
`docs/PENDING_WORK_MASTER.md`, `README.md`.

## Earlier history

See `docs/MASTER_INTEGRATION_AUDIT.md` (what was merged, from where, and why),
`docs/IMPLEMENTATION_STATUS.md` Stages 0-6, and `docs/DEVELOPMENT_HANDOFF.md` for the full
record of everything before 2026-09-24.
