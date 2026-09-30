# HANDOFF_PACKAGE.md

## 1. What the project is and the current objective

iTantra (SIH26173): an offline, on-device, multilingual (10 Indian
languages) speech-to-speech translation and disaster-alert relay app for
Android, communicating peer-to-peer over Wi-Fi Direct/BLE mesh with no
runtime internet dependency. Governing spec:
`iTantra_SIH26173_Master_Development_Prompt.md` (see `docs/DECISIONS.md`
entry #1). Current objective: close Gate 1 (§16) — repository & architecture
skeleton — then proceed gate-by-gate per `docs/TODO.md`.

## 2. What this session was specifically assigned to implement

The user asked (across two messages, after correcting an initial wrong file
upload) to read the master prompt and begin development. The master
prompt's own closing instruction is explicit: initialize the repository per
§15, log ingestion in `DECISIONS.md` with today's date and a one-line Class
L summary, then begin Gate 1 (§16) — "do not produce another planning
document, produce the repository."

## 3. Everything successfully completed this session

- Full repository scaffold matching §15's module layout, git-initialized.
- **Real, functionally-verified implementations** (not stubs) in four
  pure-Kotlin/JVM modules:
  - `core`: all §14 domain contracts (`AudioFrame`, `RecognizedUtterance`,
    `SemanticPacket`, `ChannelStats`, `Language` enum with all 10 codes,
    `DeviceClass`) + `EndpointStateMachine` (§19's 5-state endpointer).
  - `protocol`: `PacketCodec`/`Crc16` — full §24 wire framing, fail-closed
    decode, resolves the source doc's 24-vs-26-byte header inconsistency
    (see `docs/DECISIONS.md` entry #2). Fuzz-tested.
  - `vad`: `EnergyGate` — real Stage-1 §19 energy/noise-adaptive VAD.
  - `codec`: `Utf8BaselineCodec` — the mandatory §35 comparison baseline,
    verified round-tripping multi-byte Hindi/Tamil UTF-8 correctly.
  - `benchmark`: `TelemetryEvent`/`JsonlTelemetryWriter` — hand-rolled JSON
    (no Maven Central access in this sandbox to add a JSON library).
- **Honest `NotImplementedError` interface stubs** (no fabricated data) for
  everything Gate 1 doesn't require implemented: `stt`, `tts`,
  `language-id`'s LID path, `codec/SubwordSemanticCodec`,
  `wifi/WifiTransport`, `bluetooth/BleTransport`, `audio`'s capture/
  playback, `alert-engine`, and `android-app`'s Application/Activity/Service
  shell.
- Full Gradle multi-module setup: root `build.gradle.kts`/`settings.gradle.kts`,
  every module's own `build.gradle.kts`, a real generated Gradle wrapper
  (`gradlew`/`gradlew.bat`/`gradle-wrapper.jar`, targeting Gradle 8.9).
- `.github/workflows/ci.yml` (build+test, ktlint, a tools smoke-test job)
  and `.github/workflows/scheduled-device-tests.yml` (honest placeholder
  per §39's per-commit vs. scheduled test split).
- Three real, tested Python CLI tools in `tools/`: `loss_injection.py`
  (UDP loss/reorder/delay relay, loopback-tested), `benchmark_bundle.py`
  (telemetry aggregator, tested against synthetic data), `model_scorecard.py`
  (generate/validate, tested end-to-end including the reject-on-blank case).
- `datasets/schemas/dataset_manifest.schema.json` — a real, JSON-valid
  schema for future dataset provenance manifests.
- All required docs: `README.md`, `docs/ARCHITECTURE.md`, `docs/TODO.md`,
  `docs/KNOWN_ISSUES.md`, `docs/DECISIONS.md` (4 entries), `docs/BENCHMARKS.md`
  (honest empty placeholder + schema), this handoff file.
- `.gitignore` covering Gradle/Android/Kotlin/Python/model-artifact patterns.

## 4. Files created, modified, or deleted

**Created** (everything — this was a from-scratch scaffold):
```
.github/workflows/ci.yml
.github/workflows/scheduled-device-tests.yml
.gitignore
README.md
build.gradle.kts
settings.gradle.kts
gradle.properties
gradle/wrapper/gradle-wrapper.properties
gradle/wrapper/gradle-wrapper.jar
gradlew
gradlew.bat
android-app/build.gradle.kts
android-app/src/main/AndroidManifest.xml
android-app/src/main/res/values/strings.xml
android-app/src/main/res/values/themes.xml
android-app/src/main/kotlin/com/itantra/app/ITantraApplication.kt
android-app/src/main/kotlin/com/itantra/app/MainActivity.kt
android-app/src/main/kotlin/com/itantra/app/SessionService.kt
core/build.gradle.kts
core/src/main/kotlin/com/itantra/core/Contracts.kt
core/src/main/kotlin/com/itantra/core/EndpointStateMachine.kt
core/src/test/kotlin/com/itantra/core/ContractsTest.kt
core/src/test/kotlin/com/itantra/core/EndpointStateMachineTest.kt
audio/build.gradle.kts
audio/src/main/AndroidManifest.xml
audio/src/main/kotlin/com/itantra/audio/AudioIo.kt
alert-engine/build.gradle.kts
alert-engine/src/main/AndroidManifest.xml
alert-engine/src/main/kotlin/com/itantra/alert/AlertEngine.kt
vad/build.gradle.kts
vad/src/main/kotlin/com/itantra/vad/VadGate.kt
vad/src/test/kotlin/com/itantra/vad/EnergyGateTest.kt
language-id/build.gradle.kts
language-id/src/main/kotlin/com/itantra/langid/LanguageRouter.kt
stt/build.gradle.kts
stt/src/main/kotlin/com/itantra/stt/SttEngine.kt
tts/build.gradle.kts
tts/src/main/kotlin/com/itantra/tts/TtsEngine.kt
codec/build.gradle.kts
codec/src/main/kotlin/com/itantra/codec/SemanticCodec.kt
codec/src/test/kotlin/com/itantra/codec/Utf8BaselineCodecTest.kt
protocol/build.gradle.kts
protocol/src/main/kotlin/com/itantra/protocol/Packet.kt
protocol/src/main/kotlin/com/itantra/protocol/PacketTransport.kt
protocol/src/test/kotlin/com/itantra/protocol/PacketCodecTest.kt
wifi/build.gradle.kts
wifi/src/main/AndroidManifest.xml
wifi/src/main/kotlin/com/itantra/transport/wifi/WifiTransport.kt
bluetooth/build.gradle.kts
bluetooth/src/main/AndroidManifest.xml
bluetooth/src/main/kotlin/com/itantra/transport/ble/BleTransport.kt
benchmark/build.gradle.kts
benchmark/src/main/kotlin/com/itantra/benchmark/TelemetryEvent.kt
benchmark/src/test/kotlin/com/itantra/benchmark/TelemetryEventTest.kt
native/README.md, native/cpp/.gitkeep, native/jni/.gitkeep
training/README.md, training/scripts/.gitkeep, training/configs/.gitkeep
datasets/README.md, datasets/manifests/.gitkeep, datasets/schemas/dataset_manifest.schema.json
tools/loss_injection/loss_injection.py, tools/loss_injection/tests/test_loopback.py
tools/benchmark_bundle/benchmark_bundle.py
tools/model_scorecard/model_scorecard.py
tests/README.md + .gitkeep in unit/integration/hardware/model/network/stress
experiments/README.md
docs/ARCHITECTURE.md, docs/TODO.md, docs/KNOWN_ISSUES.md, docs/DECISIONS.md,
docs/BENCHMARKS.md, docs/handoff/HANDOFF_PACKAGE.md
```
**Modified/deleted**: none (nothing pre-existed).

## 5. Exact current project structure

See `docs/ARCHITECTURE.md`'s module map diagram, or run `find . -type f -not -path './.git/*'` from repo root.

## 6-9. Current implementation status / working / partial / broken

See `docs/ARCHITECTURE.md`'s "What's real vs. stub right now" section and
`docs/KNOWN_ISSUES.md` in full — both are more precise and less likely to
drift out of sync than duplicating the list a third time here. Summary:
4 modules (`core`, `protocol`, `vad`, `codec`) + `benchmark` have real,
tested logic; everything Android-specific and all model-dependent code is
an honest stub; nothing is "broken" in the sense of committed-but-failing —
what's uncertain is whether the Android/Gradle-toolchain-dependent modules
will compile under the real toolchain, since that's never been run (see
`docs/KNOWN_ISSUES.md`, "Authoring-environment limitations").

## 10. Exact errors, logs, and stack traces

None outstanding. One real bug was caught and fixed during this session: an
early draft of `PacketCodec.encode()` wrote the magic/version byte twice
(once as two separate `put()` calls, then rewound and overwritten) — caught
by a `kotlinc`-only sanity compile, not by a test, and cleaned up before any
test ran against it. No other errors encountered that weren't immediately
resolved (see item 11 for the loss_injection.py test's initial
methodology bug, also caught and fixed within this session).

## 11. Tests that were run and their results

Run manually in this sandbox (no CI access — see `docs/KNOWN_ISSUES.md`):
- `core`, `protocol`, `vad`, `codec`, `benchmark` main sources: compiled
  cleanly with `kotlinc` (via a scratch copy with `entries`→`values()` and
  `data object`→`object` substituted, to work around the sandbox's Kotlin
  1.3.31 vs. the project's real Kotlin 2.0.20 target — see DECISIONS.md #3).
- `protocol`: ad hoc `main()` smoke test — round-trip, CRC-corruption
  detection, truncated-frame handling, empty-array handling, and a
  20,000-iteration random-garbage fuzz pass, all passed. A 5,000-iteration
  version of the fuzz pass is now a **committed** JUnit test.
- `vad`: ad hoc smoke test — silence not classified as speech, loud tone
  classified as speech, passed.
- `codec`: ad hoc smoke test — multi-byte Hindi+Tamil UTF-8 round-trip,
  token-count-equals-byte-count, bits/sec formula, unimplemented-stub-throws,
  all passed.
- `benchmark`: ad hoc smoke test — null encoding, numeric encoding, string
  escaping (quotes/backslashes), multi-line JSONL writer, all passed.
- `tools/loss_injection/loss_injection.py`: `tools/loss_injection/tests/test_loopback.py`
  run directly — PASS (200 packets, ~18.5% observed loss vs. 20% configured,
  received count exactly matches seen-minus-dropped).
- `tools/benchmark_bundle/benchmark_bundle.py`: run against 50 synthetic
  telemetry events — correct aggregation, correct "(no data)" handling for
  an unpopulated field.
- `tools/model_scorecard/model_scorecard.py`: full round trip — blank
  template correctly fails `validate` (16 problems listed), filled-in
  version correctly passes.
- **Not run**: `./gradlew build` (no compatible local Gradle/Android SDK —
  see `docs/KNOWN_ISSUES.md`), `ktlintCheck` (same reason), the actual
  `.github/workflows/*.yml` files (never executed on GitHub Actions).

## 12. Benchmarks already measured

None. Gate 1 is scaffolding, not measurement — see `docs/BENCHMARKS.md`
(intentionally empty with a documented template for the first real entry).

## 13. Known limitations

Full list, with reasoning, in `docs/KNOWN_ISSUES.md`. Top items: no model
integration anywhere, no transport implementation, Android modules
completely unverified locally (no SDK in this sandbox), CI has never
actually run.

## 14. Architectural decisions made and why

`docs/DECISIONS.md`, entries #1-4: (1) which document governs and the
Class L target; (2) CRC-as-trailer resolves the header byte-count
ambiguity; (3) modern Kotlin syntax used despite an old local compiler;
(4) pure-JVM vs. Android module split rationale.

## 15. Dependencies added/removed and their versions

- Android Gradle Plugin 8.5.2, Kotlin 2.0.20 (root `build.gradle.kts`)
- Gradle wrapper: 8.9
- `org.jlleitschuh.gradle.ktlint` 12.1.1 (root `build.gradle.kts`, applied
  to all subprojects)
- JUnit Jupiter 5.10.2 (test-only, in `core`/`protocol`/`vad`/`codec`/`benchmark`)
- `androidx.core:core-ktx:1.13.1`, `androidx.appcompat:appcompat:1.7.0`
  (`android-app` only)
- **None of the above have been dependency-resolved for real** — this
  sandbox cannot reach Google's Maven repo or Maven Central. Versions were
  chosen as reasonable current picks, not verified compatible with each
  other. First real signal: CI.

## 16. Model files, versions, checksums, locations

None. No model has been selected, downloaded, or referenced by path
anywhere in the repository. `datasets/manifests/` and `training/` are
empty by design (see their READMEs).

## 17. Configuration/environment requirements

JDK 17, Android SDK (compileSdk 35, minSdk 26 — see the `[ASSUMPTION,
ENGINEERING]` note in `docs/ARCHITECTURE.md`), network access to Google's
Maven repo + Maven Central + Gradle's distribution server for the first
build. Python 3.12+ for `tools/*.py` (stdlib only, no extra pip installs
required for what exists today).

## 18. Commands to build, run, test, benchmark

```bash
./gradlew build                      # compile + unit-test every module
./gradlew ktlintCheck                # static analysis
python3 tools/loss_injection/tests/test_loopback.py
python3 tools/benchmark_bundle/benchmark_bundle.py <file.jsonl> --out report.md
python3 tools/model_scorecard/model_scorecard.py new --model-name X --out scorecards/X.md
python3 tools/model_scorecard/model_scorecard.py validate scorecards/X.md
```
No app-run command yet — `android-app` has no functioning UI (Gate 2+).

## 19. Exact task that should be continued next

Get `.github/workflows/ci.yml` to actually run green on GitHub Actions
(push this repo to a real GitHub repo, or otherwise get CI execution). This
is the one item separating "Gate 1 should be done" from "Gate 1 is done"
per §46 — everything else in Gate 1's exit criteria is met.

## 20. Prioritized remaining tasks

1. Get CI running for real; fix whatever `./gradlew build` and
   `ktlintCheck` surface that this sandbox couldn't catch.
2. Close Gate 1 formally in `docs/TODO.md` once CI is green.
3. Begin Gate 2: pick and benchmark one STT + one TTS model candidate,
   produce their Model Scorecards (§18) via `tools/model_scorecard/`,
   implement real `AudioCapture`/`AudioPlayback` in `audio/`.
4. Pick the actual Class L reference device (physical or emulator profile)
   and validate/update the `minSdk`/RAM assumptions in `docs/ARCHITECTURE.md`.

## 21. Dangerous areas the next agent should not blindly modify

- `protocol/Packet.kt`'s byte offsets — every offset is derived from §24's
  field order; changing field order without updating both `encode()` and
  `decode()` (and the offset-comment in `PacketCodecTest.kt`'s
  `unknown message type` test) will silently desync them.
- `core/Contracts.kt`'s `AudioFrame`/`SemanticPacket` custom
  `equals`/`hashCode` overrides exist specifically because they contain
  array fields (`ShortArray`/`IntArray`/`ByteArray`), which don't get
  structural equality for free in Kotlin — removing these overrides during
  a refactor would silently break value-equality-dependent test assertions.

## 22. Assumptions that still need validation

- `minSdk = 26` across all Android modules — `[ASSUMPTION, ENGINEERING]`,
  not sourced from the master prompt (see `docs/ARCHITECTURE.md`).
- Gradle 8.9 / AGP 8.5.2 / Kotlin 2.0.20 mutual compatibility — chosen, not
  verified (no network access to actually resolve and build with them).
- `android-actions/setup-android@v3` in CI — assumed current; not
  re-verified against its repo this session.

## 23. Research or technical decisions that remain unresolved

STT/TTS/LID model selection (§18's candidate matrices are still open — no
Model Scorecard has been started, only the tool to enforce filling one out
correctly). Whether `SubwordSemanticCodec` will be a from-scratch BPE
tokenizer or adapt an existing one is also unresolved.

## 24. Recommended exact next 3-5 actions

1. Push this repository to GitHub (or otherwise get `.github/workflows/ci.yml`
   executing) and fix whatever the real toolchain surfaces.
2. Mark Gate 1 closed in `docs/TODO.md` once CI is green; add a
   `docs/DECISIONS.md` entry recording that closure and the CI run's URL/SHA.
3. Start Gate 2 by researching STT candidates per §18's matrix
   (IndicConformer, sherpa-onnx, Vakyansh) for one initial language, and
   drafting the first real Model Scorecard with
   `tools/model_scorecard/model_scorecard.py new`.
4. Implement real `AudioCapture` in `audio/` (AudioRecord-backed),
   replacing `UnimplementedAudioCapture`.
5. Once both of the above exist, wire Gate 2's end-to-end loop (speak → STT
   text in UI → TTS speaks it back) and record the first real
   `docs/BENCHMARKS.md` entry from it.
