# iTantra-Master — SIH26173

Offline, on-device, multilingual (10 Indian languages) speech-to-speech translation and
disaster-alert relay over Wi-Fi/BLE, with no runtime internet dependency.

This repository is the result of merging four previously separate project archives
(a Gate 1 architecture skeleton, a more complete Gate 1 implementation, a speech/language
stack, and a communication stack) into one integrated, buildable project. **Read
`docs/MASTER_INTEGRATION_AUDIT.md` first** if you're new to this repository — it explains
what was merged, from where, and why, and links to the other three integration documents
(`docs/FILE_CONFLICT_MATRIX.md`, `docs/PENDING_WORK_MASTER.md`,
`docs/DEVELOPMENT_HANDOFF.md`).

## Current status table

Every capability below is stated as one of: **Working and tested**, **Working but
limited**, **Implemented but untested**, **Partial**, **Simulation only**,
**Architecture only**, **Missing**, or **Blocked**. See `docs/MASTER_INTEGRATION_AUDIT.md`
§4 and `docs/PENDING_WORK_MASTER.md` for what each status is based on.

| Capability | Status | Notes |
|---|---|---|
| Repository integration | **Working and tested** | One package root, one Gradle multi-module layout, one canonical contract set. See `docs/FILE_CONFLICT_MATRIX.md`. |
| Gradle build (`./gradlew build`) | **Blocked** | Never run — no Maven Central/Google Maven/Gradle-distribution/Android SDK access in any session's sandbox to date. Re-confirmed 2026-09-23. See `docs/PENDING_WORK_MASTER.md` P0.1. |
| Main source compiles (pure-JVM modules) | **Verified against the real target compiler, 2026-09-23; extended 2026-09-24 (twice)** | All 34 main-source files across `core`/`vad`/`language-id`/`stt`/`tts`/`codec`/`protocol`/`bluetooth`/`benchmark`, unmodified, compiled with the genuine `kotlinc-jvm 2.0.20` (fetched from GitHub releases — reachable even though Maven/Gradle infra isn't) — 0 errors, 0 warnings, 102 classes. 2026-09-24: `alert-engine` (HEMIM) added, then `codec`+`protocol`+the new `:orchestrator` module added — **101 classes, 0 errors, 0 warnings** for the full `core`+`alert-engine`+`codec`+`protocol`+`orchestrator` stack. This does **not** mean the module's real Android/Gradle build works, only that this extra verification path is available for the parts of the repo with no `android.*` import, unlike `audio`/`android-app`/`wifi`/`bluetooth`. Still not a Gradle run; JUnit tests still not executed (junit-jupiter jars are Maven-Central-only). See `tests/build-logs/2026-09-23_real_kotlinc_2.0.20_main_compile.txt`, `tests/build-logs/2026-09-24_hemim_v1_core_alert-engine_compile.txt`, and `tests/build-logs/2026-09-24_orchestrator_full_stack_compile.txt`. |
| Android APK | **Blocked** | No Android SDK available to attempt assembly. |
| Audio capture | **Implemented but untested** | `AndroidAudioCapture` (real `AudioRecord`), added 2026-09-27 — see P1.1. Not on the app's only working recognition path today (see Speech-to-text row); `UnimplementedAudioCapture` also still exists unchanged. Never compiled (no Android SDK/network in this session's sandbox). |
| Audio playback | **Implemented but untested** | `AndroidAudioPlayback` (real `AudioTrack`), added 2026-09-27, wired into `SessionService` for both normal and alert playback — see P1.2. Never compiled. |
| VAD (energy-gate) | **Implemented, runtime-verified this session** | `EnergyGateVad` + `Endpointer`, real logic, exercised by the smoke test against synthetic PCM. Never run against real microphone audio. |
| Neural VAD | **Architecture only** | `NeuralVadPort` interface + honest stub. Not started, lowest priority by design. |
| STT (real offline) | **Implemented but untested, outside the `SttEngine` interface** | `AndroidOnDeviceSpeechRecognizer` (`android-app/.../stt/`), added 2026-09-27, wraps Android's on-device `SpeechRecognizer` — real, working when the device/OS supports it, but deliberately does NOT implement `stt.SttEngine` (architecture reasons in `docs/DECISIONS.md` Entry #7). No engine implementing `SttEngine` itself exists yet — `SimulationBackedSttEngine` remains explicitly non-functional-by-design. Never compiled. See P1.3. |
| TTS (real offline) | **Implemented but untested** | `AndroidTextToSpeechEngine` (`android-app/.../tts/`), added 2026-09-27, implements `tts.TtsEngine` using Android's `TextToSpeech`. Offline capability is device/voice-dependent and only heuristically detectable — see the class's KDoc. Never compiled. See P1.4. |
| Language routing (selection) | **Implemented, runtime-verified this session** | `LanguageRouter`'s explicit-selection + confidence-gated LID override, tested. |
| Translation | **Missing** | No component anywhere translates between languages — routing is not translation. See `docs/PENDING_WORK_MASTER.md` P3.1; this is flagged as the single largest gap. |
| Semantic codec (baseline) | **Implemented, runtime-verified this session** | `Utf8BaselineCodec`, the mandatory comparison baseline for any compression claim. |
| Semantic codec (BPE/subword) | **Implemented, measured on a placeholder corpus, runtime-verified this session** | `BpeSemanticCodec`, ~40-60% of baseline size on a small non-representative corpus (`codec/validation/RESULTS.md`). Not trained on a real per-language corpus, not benchmarked on-device. |
| Adaptive channel controller | **Implemented, runtime-verified this session** | Real hysteresis + thermal/battery hard gates. Thresholds are `[TARGET]`, not measured (P4.1). |
| Protocol / packet framing | **Implemented, runtime-verified this session** | CRC-16, fail-closed decode, a documented timestamp-wraparound fix. |
| Fragmentation | **Implemented, runtime-verified this session** | Send-side, in `WifiUdpTransport`/`BleFragmenter`. |
| Fragment reassembly | **Implemented, runtime-verified this session** | New this session: `FragmentReassembler.kt` — this exact gap was predicted by the integration brief and confirmed missing before being written. |
| Wi-Fi transport | **Implemented, validated against a real UDP loopback (by Part IV, prior session); not run in this session** | `WifiUdpTransport` over plain `DatagramSocket`. Never run on an Android device; no Wi-Fi Direct/hotspot discovery. Real goodput accounting added 2026-09-23 (P4.5), arithmetic verified in isolation — see `wifi/validation/`. Still cannot be compiled in this sandbox (`kotlinx-coroutines` unreachable) — reconfirmed 2026-09-23, identical error set. |
| BLE transport | **Partial — real logic, zero hardware integration** | MTU-aware fragmentation, windowed ACK, and the `MINIMUM_FUNCTIONAL_MTU` finding are real and unit-tested. No `BluetoothGatt` wiring exists (P4.2). |
| Alert engine (audio-focus/notification policy) | **Implemented but untested** | `AndroidAlertEngine`, added 2026-09-27: real `AudioManager` focus request + `NotificationManager` high-priority channel. Does not detect active calls (no `READ_PHONE_STATE` requested) or simulate fine-grained DND policy — see the class's KDoc. `UnimplementedAlertEngine` also still exists unchanged. Never compiled. |
| Session orchestration (`SessionService`) / UI | **Implemented but untested** | Added 2026-09-27: `SessionService` (real `SessionStateMachine` + wires STT/TTS/orchestrator/transport into one push-to-talk pipeline) and `MainActivity` (real, programmatically-built UI), replacing both Gate-1 shells. See P1.5/P2.1/P2.8 and `docs/KNOWN_ISSUES.md`'s entry for this session for the full, honest verification status — notably, this session's sandbox could not run any Kotlin compiler at all, not even the GitHub-hosted substitute a prior session used. |
| HEMIM emergency-message analysis (V1-V3: rules + entities + negation/context) | **Implemented, real-executed this session** | New 2026-09-24, in the `alert-engine` module. `RuleBasedEmergencyAnalyzer` classifies English text into category/priority/confidence with critical-entity extraction and negation/question/historical-framing handling. 64/64 checks passed running the real compiled classes (not a reimplementation) — see `docs/HEMIM.md` and `alert-engine/validation/RESULTS.md`. English only; no ML classifier (V4), no evidence fusion beyond the V1-V3 baseline (V5), no multilingual support (V6). Now wired into a real message envelope and priority queue — see the next row. |
| `:orchestrator` — full message pipeline (HEMIM -> priority -> encode -> fragment -> queue -> transport, and the receive mirror) | **Implemented, real-executed this session** | New module, new 2026-09-24 (`docs/ORCHESTRATOR.md`). 37/37 checks passed end-to-end over a loopback transport (normal + emergency messages, fragmentation, corruption, duplicate-frame rejection, dropped-ACK honesty, TTL expiration) — see `orchestrator/validation/RESULTS.md`, which also documents a real delivery-status bug found and fixed via this testing. Found and fixed two pre-existing gaps: no `SemanticPacket`<->wire-bytes codec existed anywhere in the repo (now `protocol.SemanticPacketWireCodec`), and fragmentation logic was duplicated between `wifi`/`bluetooth` (now shared via `protocol.PacketFragmenter`, with `WifiUdpTransport` refactored to use it — reviewed but not recompiled, see caveat below). **Not wired to a real transport**: `WifiUdpTransport` doesn't implement the `PacketTransport` interface this module depends on (a real architectural gap, not yet resolved — `docs/PENDING_WORK_MASTER.md` P2.6). Not wired into `android-app` (no Android SDK in this sandbox to verify against). No real STT feeding it. |
| Two-device demo | **Missing** | Not attempted; see P2.2. |
| Multilingual demo | **Missing** | Depends on P1.3/P1.4 (real STT/TTS) and P3.1 (translation), none of which exist yet. |
| Benchmarking | **Partial** | `TelemetryEvent`/`JsonlTelemetryWriter` real and tested; no real device has ever produced telemetry through this pipeline. |

## Repository layout

```
android-app/    UI, lifecycle, permissions, foreground session (Division C) — Android SDK required
core/           shared contracts, state vocabulary, language registry, latency stats
audio/          AudioRecord/AudioTrack capture & playback (Division C) — Android SDK required
alert-engine/   audio-focus + notification priority policy (Division C, Android SDK required
                for the real Gradle build) + HEMIM offline emergency-message analysis (new
                2026-09-24, pure Kotlin/JVM — see docs/HEMIM.md)
vad/            two-stage VAD: energy gate (real) + endpointer (real) + neural stub (Division A)
language-id/    session language routing + confidence-gated auto-LID (Division A)
stt/            SttEngine interface + a labeled simulation-only bridge; no real recognizer (Division A)
tts/            TtsEngine interface + text front-end + latency tracker; no real synthesizer (Division A)
codec/          two real codecs (UTF-8 baseline, BPE subword) + adaptive channel controller (Division B)
protocol/       packet framing, CRC-16, reliability layer, fragment reassembler, packet
                fragmenter, SemanticPacket<->wire codec (Division D)
wifi/           real UDP transport, pure Kotlin/JVM (Division D) — no Android SDK required
bluetooth/      real MTU/fragmentation/ACK logic, pure Kotlin/JVM for now (Division D) — see docs/PENDING_WORK_MASTER.md P4.2
orchestrator/   cross-cutting message pipeline: HEMIM -> priority -> encode -> fragment ->
                queue -> transport, and the receive-side mirror (new 2026-09-24, pure
                Kotlin/JVM — see docs/ORCHESTRATOR.md)
native/         JNI hot loops only — empty until profiling says otherwise
training/       fine-tuning/conversion scripts — empty, no model chosen yet
datasets/       dataset provenance/license manifests — empty, none ingested yet
benchmark/      telemetry event schema + JSONL writer
tools/          loss-injection relay, benchmark aggregator, model-scorecard CLI
tests/          cross-module / hardware / model / network / stress tests, plus run-logs/
experiments/    dated lab-notebook entries, one file per experiment
docs/           MASTER_INTEGRATION_AUDIT.md, FILE_CONFLICT_MATRIX.md, PENDING_WORK_MASTER.md,
                DEVELOPMENT_HANDOFF.md, ARCHITECTURE.md, TODO.md, KNOWN_ISSUES.md, DECISIONS.md,
                BENCHMARKS.md
```

Note: `wifi` and `bluetooth` are plain Kotlin/JVM modules, not Android libraries — see
`docs/FILE_CONFLICT_MATRIX.md` for why. Everything else follows the same module split as
before the merge.

## Building

```bash
./gradlew build
```

This has not been run to completion in any environment this repository has existed in so
far — see `docs/PENDING_WORK_MASTER.md` P0.1 for exactly why and what to do about it.
Requires JDK 17, the Android SDK (for `android-app`/`audio`/`alert-engine`), and network
access to Google's Maven repo, Maven Central, and the Gradle distribution server on first
run.

## Tools

```bash
# Simulate a lossy/reordering channel between two local endpoints
python3 tools/loss_injection/loss_injection.py --listen 127.0.0.1:9001 \
    --forward-to 127.0.0.1:9002 --loss 0.1 --seed 42

# Aggregate telemetry JSONL into a markdown report
python3 tools/benchmark_bundle/benchmark_bundle.py telemetry.jsonl --out report.md

# Generate / validate a Model Scorecard before adopting any STT/TTS/LID model
python3 tools/model_scorecard/model_scorecard.py new --model-name my-model --out scorecards/my-model.md
python3 tools/model_scorecard/model_scorecard.py validate scorecards/my-model.md
```

## Where to start reading

1. `docs/MASTER_INTEGRATION_AUDIT.md` — what was merged, from where, and why
2. `docs/FILE_CONFLICT_MATRIX.md` — every duplicate/conflicting file and the reasoning behind its resolution
3. `docs/PENDING_WORK_MASTER.md` — the prioritized (P0–P5) backlog
4. `docs/DEVELOPMENT_HANDOFF.md` — exact commands run, exact results, what to do next
5. `docs/KNOWN_ISSUES.md` — what's unverified and why, stated plainly
6. `docs/HEMIM.md` — the offline emergency-message analysis subsystem (new 2026-09-24): design, version roadmap, algorithm, limitations, verification evidence
7. `docs/ORCHESTRATOR.md` — the message pipeline connecting HEMIM, encoding, framing, and transport (new 2026-09-24): design, gaps found and fixed, what's still blocked
8. `docs/TESTING.md` — index of all test evidence: exact commands, what's real-executed vs. unexecuted source, and where each result lives
9. `docs/CHANGELOG.md` — dated log of significant changes
