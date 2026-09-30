# TESTING.md

This file is an index into the test evidence that already lives next to the code it tests
(each module's `validation/RESULTS.md`, plus `tests/build-logs/`). It does not repeat that
evidence — it tells you where to find it and gives the exact commands to reproduce it.

## The two kinds of test evidence in this repository, and why

1. **JUnit5 test sources** (`*/src/test/kotlin/`) — the project's normal, intended test
   form, meant to run via `./gradlew <module>:test` (pure-JVM modules) or
   `./gradlew <module>:testDebugUnitTest` (Android-library modules like `alert-engine`).
   **None of these have been executed by Gradle in this authoring sandbox** — `junit-jupiter`
   jars live on Maven Central, which is off this sandbox's network allow-list (see
   `docs/KNOWN_ISSUES.md`). They are real, reviewed source, not proof of passing.
2. **Standalone runtime smoke tests** (`*/validation/*.kt`, plain `fun main()` files, no test
   framework) — written specifically because (1) isn't executable here. Each one has been
   compiled with a real `kotlinc-jvm 2.0.20` (fetched from GitHub releases, which *is*
   reachable) and actually **run**, with output captured verbatim into a `.txt` file next to
   it. Where a smoke test and a JUnit file cover the same logic, they contain the identical
   assertions — the smoke test is not weaker coverage, it's the same coverage executed
   through a different mechanism because the standard one isn't available here.

**When real tooling (Android SDK + Maven access) is available**, run:
```
./gradlew test                    # every pure-JVM module's JUnit5 suite
./gradlew testDebugUnitTest        # every Android-library module's JUnit5 suite
./gradlew connectedAndroidTest     # instrumentation tests, if any — none exist yet
./gradlew assembleDebug            # APK build — see the top-level handover report for status
```
None of these have been run to completion in this authoring session; see below for what has.

## What has actually been executed, and where the evidence is

| Module | Real evidence | Location |
|---|---|---|
| `core`, `vad`, `language-id`, `stt`, `tts`, `codec`, `protocol`, `bluetooth`, `benchmark` (pre-existing, 2026-09-23) | Compiled clean with real `kotlinc` (0 errors/warnings, 102 classes); `protocol` additionally validated against a real kernel UDP socket in Python | `tests/build-logs/2026-09-23_real_kotlinc_2.0.20_main_compile.txt`, `protocol/validation/RESULTS.md`, `codec/validation/RESULTS.md` |
| `alert-engine` (HEMIM V1-V3, new 2026-09-24) | 64/64 real-executed checks, including every adversarial case (negation, questions, historical framing, empty input, unsupported language, ...) | `alert-engine/validation/RESULTS.md` |
| `protocol` (`PacketFragmenter`, `SemanticPacketWireCodec`, new 2026-09-24) | 15/15 real-executed checks | `protocol/validation/RESULTS.md`, `protocol/validation/packet_fragmenter_wirecodec_smoketest_output.txt` |
| `:orchestrator` (new module, 2026-09-24) | 22/22 real-executed checks end-to-end over a loopback transport, including a real bug found and fixed via this testing | `orchestrator/validation/RESULTS.md` |
| **Total new this session** | **101/101** | — |
| `android-app` (`SessionStateMachine`, new 2026-09-27) | JUnit5 source written (10 tests: happy paths, illegal-transition rejection, duplicate-listen rejection, force-stop from every live state, terminal-STOPPED non-resurrection, listener notification). **Not executed by any mechanism** — see below; this session had no working Kotlin compiler at all, not even the GitHub-hosted substitute 2026-09-23/2026-09-24 used. | `android-app/src/test/kotlin/com/itantra/app/SessionStateMachineTest.kt` |

## What has NOT been executed (stated plainly, not omitted)

- Any JUnit5 suite, anywhere, via Gradle (Maven Central unreachable in this sandbox).
- `wifi`'s or `bluetooth`'s own source, at all, by anything — both need
  `kotlinx-coroutines`, also Maven-Central-only. The `WifiUdpTransport.send()` refactor this
  session (see `docs/ORCHESTRATOR.md`) was reviewed line-by-line but not recompiled.
- `android-app` — needs the Android SDK, not present in this sandbox. This includes the
  2026-09-27 session's `SessionService`/`MainActivity`/`SessionStateMachine`/
  `AndroidOnDeviceSpeechRecognizer`/`AndroidTextToSpeechEngine`/`AndroidAlertEngine`/
  `AndroidAudioCapture`/`AndroidAudioPlayback` additions — **not even `SessionStateMachine`
  itself, despite having zero `android.*` imports and being exactly the kind of file the
  kotlinc-substitute method verified for `core`/`vad`/etc. in prior sessions**: this session's
  network allow-list did not reach GitHub either (see `docs/KNOWN_ISSUES.md`'s entry for this
  session for the exact `curl` evidence), so that substitute path was unavailable here. This is
  a strictly weaker verification bar than every session since 2026-09-23 — flagged explicitly
  so it is not mistaken for the same level of evidence.
- Anything requiring two physical devices, a real Wi-Fi/BLE radio, a microphone, or a real
  offline STT/TTS model — none of these exist as runnable artifacts in this repository yet.
- An APK build of any kind.

See the most recent session's final handover report (in the conversation, not a repo file)
for the complete, itemized accounting the driving prompt's §25 requires.
