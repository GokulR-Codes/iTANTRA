# IMPLEMENTATION STATUS — iTantra-Master

Tracks progress against `iTantra_CONTINUATION_MASTER_PROMPT.md`'s Stage 0–6 execution
order. Updated after every meaningful change, per that prompt's rule 12. This document
is the current source of truth for "what stage are we actually at" — `docs/PENDING_WORK_MASTER.md`
(P0–P5) is the older, priority-based backlog from the integration session; the two are
cross-referenced but this file tracks the newer stage-based plan specifically.

**Continuation prompt's non-negotiable rules 1–12 are being followed as stated.** In
particular: rule 2 (docs are hypotheses until verified), rule 6 (no offline STT/TTS claims
without a physical device producing real output), rule 8 (no benchmark numbers without
recorded setup + raw results). Every status below states its verification level explicitly.

---

## Stage 0 — Build verification

**Status: BLOCKED (environment), re-verified this session with fresh evidence.**

### Commands run and exact results

```
$ cd iTantra-Master && ./gradlew build
openjdk version "21.0.10" 2026-01-20
Downloading https://services.gradle.org/distributions/gradle-8.9-bin.zip

Exception in thread "main" java.io.IOException: Server returned HTTP response code: 403
for URL: https://services.gradle.org/distributions/gradle-8.9-bin.zip
    at java.base/sun.net.www.protocol.http.HttpURLConnection.getInputStream0(...)
    ... (full trace: org.gradle.wrapper.Download/Install/WrapperExecutor/GradleWrapperMain)
```

`./gradlew test`, `./gradlew :android-app:assembleDebug`, and `./gradlew ktlintCheck` all
fail identically, at the identical line (the wrapper's first step, before any of this
repository's own build logic runs at all).

Confirmed directly (not inferred) that all three hosts the build needs are blocked by this
sandbox's egress proxy, each returning `403` with `x-deny-reason: host_not_allowed`:

```
$ curl -sI https://services.gradle.org/distributions/gradle-8.9-bin.zip   # Gradle itself
$ curl -sI https://repo.maven.apache.org/maven2/                          # Maven Central
$ curl -sI https://dl.google.com/dl/android/maven2/                      # Google's Maven (AGP, androidx)
$ curl -sI https://plugins.gradle.org/m2/                                 # Gradle Plugin Portal (ktlint plugin)
```
All four returned `HTTP/2 403`.

### Classification

**Dependency/network failure**, specifically: infrastructure access, not a Gradle
configuration, Kotlin, source, or test defect. The failure occurs before Gradle itself is
even installed — nothing in this repository's `build.gradle.kts`, `settings.gradle.kts`,
or source has had a chance to run yet. There is no "smallest root cause" fix available at
the code level; the fix is running this in an environment with the required network access.

### What was done instead, to get whatever real signal is available

Re-ran the same substitute-toolchain validation established in the prior integration
session (Kotlin 1.3.31 from `apt` — the only compiler this sandbox's allow-list permits
installing — against a scratch copy with known 1.3-vs-2.0 stdlib API differences patched):

```
$ kotlinc @filelist.txt -d out.jar          # 33 main-source files, core/vad/language-id/
                                              # stt/tts/codec/protocol/bluetooth
# -> 0 errors, 104 classes

$ kotlinc @filelist2.txt -include-runtime -d smoketest.jar && java -jar smoketest.jar
# -> 18/18 runtime checks passed (Language resolution, AudioFrame equality, VAD+Endpointer
#    on synthetic PCM, LID confidence gating, both codecs round-tripping, channel
#    controller thermal gate, Packet CRC + corruption detection, FragmentReassembler
#    out-of-order handling, BLE MINIMUM_FUNCTIONAL_MTU)
```

This is the same result as the prior session, reproduced fresh in this one (not assumed
carried-over) — see `tests/unit/run-logs/2026-09-18_iTantraMaster_RuntimeSmokeTest.log` for
the original, and the commands above for this session's re-verification.

**What this does and does not prove, stated plainly per rule 2**: it proves the
non-Android, non-coroutine portion of the codebase is syntactically/type-correct under
close-but-not-identical Kotlin semantics, and that the specific logic paths the smoke test
touches behave correctly at runtime. It proves **nothing** about `./gradlew build` itself,
nothing about the Android-SDK-dependent modules (`android-app`, `audio`, `alert-engine`),
nothing about `wifi` (needs `kotlinx-coroutines`, unreachable here), and nothing about
ktlint formatting compliance (never run, same network block).

### Exact commands for a developer machine or CI runner

```bash
cd iTantra-Master
./gradlew build          # needs: JDK 17, network access to services.gradle.org,
./gradlew test           #        repo.maven.apache.org, dl.google.com, plugins.gradle.org
./gradlew ktlintCheck
./gradlew :android-app:assembleDebug   # additionally needs the Android SDK installed
                                        # (compileSdk 35, minSdk 26 per each module's
                                        # build.gradle.kts) and ANDROID_HOME/local.properties set
```

If any of these fail on a real machine, that is genuinely new information — this session's
kotlinc-based check does not cover Gradle's own dependency resolution, AGP compatibility,
or the Android-dependent modules at all. Fix forward and update this document.

### Changed files this stage

None — Stage 0 is verification only. No source was modified.

### Known limitations

- No environment with the required network access + Android SDK has been available in any
  session of this project to date. This is a recurring, structural constraint, not a
  one-off — see `docs/KNOWN_ISSUES.md`.
- ktlint formatting compliance is completely unverified (never run, ever, in any session).

---

## Stage 1 — Single-device audio

**Status, updated 2026-09-27: WRITTEN, NOT COMPILED, NOT DEVICE-TESTED — option (b) from this
entry's own prior text was taken (explicit external direction to write it now, unverified).**

`AndroidAudioCapture`/`AndroidAudioPlayback` (`audio/src/main/kotlin/com/itantra/audio/`) are
real `AudioRecord`/`AudioTrack` implementations of the `AudioCapture`/`AudioPlayback`
interfaces, replacing `UnimplementedAudioCapture`/`UnimplementedAudioPlayback` as the classes a
real caller would use (the `Unimplemented*` classes are unchanged and still exist, for any
caller that still wants a loud-failure placeholder). Real, standard `AudioRecord`/`AudioTrack`
API usage — not a simulated waveform — but per rule 6/7's own standard, **still not evidence
that "audio capture/playback works"**: this session's sandbox has no Android SDK and no network
access to fetch one (see `docs/KNOWN_ISSUES.md`'s entry for this session — notably weaker than
even the substitute-compiler workarounds prior sessions used, since this session's network
allow-list did not include GitHub either), so nothing here has been compiled by any Kotlin
compiler, let alone run against a real microphone/speaker.

For real offline STT/TTS specifically (the other half of this stage): see Stage 2 below —
Android's on-device `SpeechRecognizer`/`TextToSpeech` were wired in as the actual recognition/
synthesis path this session, with `AndroidAudioCapture` ending up *not* on that specific path
(architecture reasons in `docs/DECISIONS.md` Entry #7) despite being real and independently
usable.

See `docs/PENDING_WORK_MASTER.md` P1.1–P1.5 for the itemized breakdown this stage maps to, all
now updated to reflect this session's work.

---

## Stage 2 — Android session orchestration

**Status, updated 2026-09-27: WRITTEN, NOT COMPILED, NOT DEVICE-TESTED.** `SessionService`
(`android-app/src/main/kotlin/com/itantra/app/SessionService.kt`) was rewritten from the
Gate-1 empty `Service` shell into a real implementation: an explicit `SessionStateMachine`
(new, pure-Kotlin/JVM, with JUnit5 tests — see `SessionStateMachineTest.kt`) drives lifecycle
transitions; `startSession()` constructs a real `orchestrator.MessageOrchestrator` against a
real user-chosen `Wifi`/BLE-central/BLE-peripheral `protocol.PacketTransport`; push-to-talk
drives `AndroidOnDeviceSpeechRecognizer` -> `MessageOrchestrator.sendText()`; inbound messages
drive `AndroidTextToSpeechEngine` -> `AndroidAudioPlayback`/`AndroidAlertEngine`; a periodic
coroutine sweep calls `MessageOrchestrator.retryPending()`. `MainActivity` was similarly
rewritten into a real (programmatic, not XML-layout — see its own KDoc for why) UI bound to
`SessionService.uiState`. Full design/verification-status detail in `SessionService`'s own KDoc
and `docs/KNOWN_ISSUES.md`'s entry for this session. This closes Stage 2 and, in the same pass,
the `android-app` side of Stage 3 (P2.1) and Stage 6 (P2.8) below — real wiring existed for
neither before this session.

**Not done**: any compilation or device verification (same blocker as Stage 1, restated in
full in `docs/KNOWN_ISSUES.md`); the BLE reconnect/backoff state machine (still deferred from
2026-09-26, untouched); a real frame-fed `stt.SttEngine` (still none exists — see
`docs/DECISIONS.md` Entry #7 for why the working recognition path this session added does not
and architecturally cannot satisfy that interface).

## Stage 4 — Translation

**Status: NOT STARTED.** Unchanged this session — `SessionService` sends/receives in one
`Language` per session with no cross-language step; see `docs/PENDING_WORK_MASTER.md` P3.1.

## Stage 3 — Wi-Fi relay

**Status, updated 2026-09-26: transport-interface gap closed, in source, not compiled.**
`WifiPacketTransportAdapter` now lets `orchestrator.MessageOrchestrator` drive real
`WifiUdpTransport` traffic (see `docs/PENDING_WORK_MASTER.md` P2.6, `docs/KNOWN_ISSUES.md`).
Still blocked on Stage 0's `kotlinx-coroutines`/Maven-Central gap for compilation. **Update
2026-09-27**: Stage 1 (real on-device STT/TTS) and Stage 2 (`SessionService` wiring) are no
longer blockers at the source level — `SessionService.startSession()` now actually constructs
and drives this adapter for a Wi-Fi-configured session — but compilation/device verification
remain unfulfilled for all of it. This closes the *architectural* gap, not the
*verified/running* gap — do not read this as "Wi-Fi relay works."

## Stage 5 — Reliability and alerts
## Stage 6 — BLE

**Status, updated 2026-09-26: real GATT client/server written, in source, not compiled, not
run against hardware.** Previously NOT STARTED (correctly, per rule 11's "prefer a small
working vertical slice" and this stage's sequencing after Stage 1) — this session went ahead
of that sequencing on explicit external request. `android-app/src/main/kotlin/com/itantra/app/ble/`
now has a complete `BluetoothGattServer`/`BluetoothGattCallback`-based client and server pair
implementing `protocol.PacketTransport`, built on the pre-existing `bluetooth.BleGattTransport`
pure-logic layer. See `docs/PENDING_WORK_MASTER.md` P2.7/P2.8 and `docs/KNOWN_ISSUES.md` for
the full writeup, including which specific pieces (reconnect/backoff, API 33+ callback
overloads) were deliberately deferred rather than guessed at. This is real, standard-API
Kotlin source — not a stub, not a simulation — but per rule 6's spirit (no claims without a
physical device producing real output) it must not be read as "BLE works" until it has
actually run on two devices. **Stage 6 is not closed** by this — it is now "written, awaiting
compilation and device verification" rather than "not started." **Update 2026-09-27**:
`SessionService` now actually constructs and drives `BleGattClientTransport`/
`BleGattServerTransport` for a BLE-configured session, and `MainActivity` provides the
discovered-device list/connect UI these two classes' own KDoc named as their blocker (P2.8) —
still entirely unverified by compilation or hardware, same caveat as everywhere else in this
entry.

---

## Stage 7 — HEMIM emergency-message intelligence

**Status: V1-V3 IMPLEMENTED and real-executed, 2026-09-24. Not part of the original
continuation-prompt stage plan above** — this stage was driven by a separate, later
directive specifically requesting HEMIM (Hybrid Emergency Message Intelligence Model), an
offline text-classification subsystem for emergency-message detection. It is additive to,
and does not block or get blocked by, Stages 1-6: it operates on message text, not audio or
transport, and reuses the existing `alert-engine` module rather than any Stage 1-6 code.

**What was implemented**, all in `alert-engine/src/main/kotlin/com/itantra/alert/`:
rule-based emergency detection (`RuleBasedEmergencyAnalyzer`, English phrase registry),
critical-entity extraction (`RegexCriticalEntityExtractor`: event/location/person-count/
injury/medical-need/severity/time/direction/requested-assistance), and negation/question/
historical-framing context handling (`NegationDetector`, `ContextAnalyzer`) — HEMIM's V1,
V2, and V3 exactly as specified.

**Verification, following this project's own evidence discipline**: real `kotlinc 2.0.20`
(the same compiler Stage 0 validated against, fetched the same way — Maven Central is
unreachable here) compiled `:core` plus every new file with 0 errors/warnings. Two
standalone runner programs then called the real compiled classes directly — not a
reimplementation, unlike the Stage 3/Wi-Fi Python/coroutine-free substitutes forced by the
`kotlinx-coroutines` gap — and 64/64 checks passed, covering every adversarial case the
driving prompt names explicitly (negation, resolved events, questions, historical framing,
explicit refusal, empty/blank input, unsupported language, long input, determinism, entity-
informed escalation). Matching JUnit5 sources exist for `./gradlew
:alert-engine:testDebugUnitTest` but that Gradle run has not happened here (no Android
SDK/Maven access — identical constraint to Stage 0). Full detail: `docs/HEMIM.md`,
`alert-engine/validation/RESULTS.md`.

**Not done as part of Stage 7 itself**: the ML classifier (V4), the multilingual rule sets
and evaluation harness (V6), and any Android UI. The emergency envelope and priority-queue
integration originally listed here as future work were completed later in this same
session — see Stage 8 below and `docs/ORCHESTRATOR.md`.

---

## Stage 8 — `:orchestrator`: the message pipeline

**Status: IMPLEMENTED and real-executed, 2026-09-24 (same session as Stage 7, continued
under a later, broader driving prompt asking for a complete working application).** New
pure-Kotlin/JVM module wiring HEMIM (Stage 7) + `codec` + `protocol` into one real
outgoing/incoming message pipeline. Full design, the two pre-existing repository gaps found
and fixed (no `SemanticPacket`<->wire-bytes serialization existed anywhere; fragmentation
logic was duplicated between `wifi` and `bluetooth`), and a real bug found and fixed via
testing (a delivery-status race in synchronous-transport conditions) are all in
`docs/ORCHESTRATOR.md` — not repeated here.

**Verification**: same real-`kotlinc`-2.0.20 methodology as Stage 0/7. 101/101 real-executed
checks across HEMIM + orchestrator combined (`orchestrator/validation/RESULTS.md`,
`alert-engine/validation/RESULTS.md`). JUnit5 sources exist but have not been run by Gradle
in this sandbox (no Android SDK/Maven access — unchanged constraint from Stage 0).

**Explicitly still blocked, not attempted**: wiring to a real transport (`WifiUdpTransport`
does not implement the `PacketTransport` interface `MessageOrchestrator` depends on — a real
architectural gap, precisely documented in `orchestrator/validation/RESULTS.md`, not
guessed at or silently patched over); wiring into `android-app/SessionService.kt` (still an
honest empty shell — no Android SDK in this sandbox to compile/verify against, so this was
deliberately not touched rather than edited blind); real offline STT feeding `sendText`
(no ASR engine exists in this repository); an APK build; any physical-device test. See the
top-level handover report for the full, honest accounting the newer driving prompt
requested.
