# KNOWN_ISSUES.md

## Real audio/STT/TTS/alert + SessionService/UI wiring (2026-09-27) — supersedes nothing below, resolves gap #3 in the entry directly below this one (SessionService/UI wiring), adds new gaps

Driven by an external request to continue the end-to-end Android integration. Same structural
constraints as every prior session, re-confirmed directly this session: `curl -sI` against
`services.gradle.org`, `repo.maven.apache.org`, `dl.google.com`, `plugins.gradle.org` all
returned `403 host_not_allowed`; no Android SDK is present; and unlike the 2026-09-23 session,
this session's sandbox network allow-list did **not** include `github.com` either (confirmed by
the same `curl -sI` probe returning the same `403`), so even the GitHub-hosted-`kotlinc`
workaround that session used was not available here — **nothing in this session's changes has
been compiled with any Kotlin compiler at all**, real or substitute. Every file below was
written and manually re-read against the exact interfaces/contracts it calls, not verified by a
toolchain. Treat this session's evidence bar as strictly weaker than 2026-09-23's and
2026-09-26's, not equal to it.

**New, real (per the definition every prior entry in this file uses: standard API usage, not a
stub/simulation) source, in `audio` and `android-app`:**

1. **`AndroidAudioCapture`/`AndroidAudioPlayback`** (`audio/src/main/kotlin/com/itantra/audio/`)
   — real `AudioRecord`/`AudioTrack` implementations of the `AudioCapture`/`AudioPlayback`
   interfaces that were `Unimplemented*`-only before this session (P1.1/P1.2). See gap #1 below
   for why `AndroidAudioCapture` is not actually on the STT input path today despite being real
   and correct.
2. **`AndroidOnDeviceSpeechRecognizer`** (`android-app/.../stt/`) — real
   `android.speech.SpeechRecognizer`/`createOnDeviceSpeechRecognizer` wiring (P1.3). Deliberately
   does **not** implement `stt.SttEngine` — see gap #1 below and `docs/DECISIONS.md` Entry #7.
3. **`AndroidTextToSpeechEngine`** (`android-app/.../tts/`) — real `android.speech.tts.TextToSpeech`
   implementation of the `tts.TtsEngine` interface (P1.4), bridging TTS's async API to
   `TtsEngine.synthesize()`'s synchronous contract via `synthesizeToFile` + a bounded
   `CountDownLatch` wait, then parsing the WAV it writes back into `SynthesizedAudio` PCM with a
   small hand-written chunk-walking WAV reader (no third-party WAV library — none is reachable
   over this sandbox's network anyway).
4. **`AndroidAlertEngine`** (`android-app/.../alert/`) — real `AudioManager`
   focus-request/`NotificationManager` high-priority-channel implementation of `alert.AlertEngine`.
   Explicitly does NOT do call-state detection (`READ_PHONE_STATE` not requested — no feature
   needs it) or fine-grained DND-policy simulation — see the class's own KDoc for exactly what
   `wouldSuppressNormalPriority()` can and cannot detect.
5. **`SessionService` rewritten from the Gate-1 empty shell to a real implementation** (P1.5,
   P2.1, P2.8 in one pass): owns a `SessionStateMachine` (new, pure-Kotlin/JVM, see
   `SessionStateMachine.kt` + its JUnit5 tests — the one part of this session's changes with
   *some* independent logic-level test coverage, albeit unexecuted, same as every other JUnit
   source in this repository per the Stage 0 caveat), constructs a real
   `orchestrator.MessageOrchestrator` against a real `Wifi`/BLE-central/BLE-peripheral
   `protocol.PacketTransport` chosen by the user, drives push-to-talk via
   `AndroidOnDeviceSpeechRecognizer`, speaks inbound messages via `AndroidTextToSpeechEngine` +
   `AndroidAudioPlayback`/`AndroidAlertEngine`, and runs a periodic `retryPending()` sweep. Full
   design reasoning in the class's own KDoc.
6. **`MainActivity` rewritten** from the Gate-1 shell to a real (deliberately, see its own KDoc,
   *programmatically constructed rather than XML-layout-based* — this sandbox cannot resolve
   `R.layout`/`R.id` to catch a typo, so a hand-written XML layout would be a materially riskier
   unverified surface than equivalent Kotlin `View` construction) UI: language selection,
   Wi-Fi/BLE connection controls including a real BLE discovered-device list (§5's Device
   Connection screen, closing gap #3 from the entry below), push-to-talk, and live
   sent/received/diagnostic status bound to `SessionService.uiState`.

**Gaps found, addressed or explicitly deferred:**

1. **`AndroidOnDeviceSpeechRecognizer` cannot implement `stt.SttEngine`.** `SttEngine.onFrame`
   expects an engine that *consumes* externally-captured `AudioFrame`s; Android's
   `SpeechRecognizer` captures the microphone itself and only returns whole-utterance results.
   Implementing the interface anyway would mean either silently ignoring every frame passed to
   `onFrame` (misrepresenting the interface) or opening a second concurrent microphone input
   alongside `AndroidAudioCapture` (fails/produces garbage audio on most devices). Recorded as an
   explicit architecture decision, not a silent gap — `docs/DECISIONS.md` Entry #7.
   **Consequence**: `AndroidAudioCapture`, while real and correct against its own interface, is
   not actually exercised by `SessionService`'s only working recognition path today. It is wired
   in and ready the moment a real frame-fed `SttEngine` (e.g. a Vosk/sherpa-onnx model) exists —
   none does yet, see P1.3 in `docs/PENDING_WORK_MASTER.md`.
2. **TTS "first-audio latency" is not meaningful for this specific engine choice.**
   `TextToSpeech.synthesizeToFile` produces the whole clip before returning; there is no
   real first-chunk callback to hook. `AndroidTextToSpeechEngine.synthesize()` reports
   `firstAudioReadyTsMs` honestly equal to total synthesis time rather than fabricating an
   earlier number — see that class's KDoc.
3. **Offline capability for both STT and TTS is a heuristic, not a guarantee**, exactly per
   Module B/E's own instruction not to assume this. `AndroidOnDeviceSpeechRecognizer.isAvailable()`
   is only authoritative on API 33+ (`isOnDeviceRecognitionAvailable`); `AndroidTextToSpeechEngine.isLikelyOffline()`
   infers from the selected `Voice`'s advertised feature flags, which engines are not required to
   report accurately. Both are surfaced to the UI labeled as such, not as a flat "offline: yes/no".
4. **No translation.** Still true, still `docs/PENDING_WORK_MASTER.md` P3.1, still not attempted
   this session — `SessionService` uses one `Language` for both STT input and TTS output; a
   received message is spoken back in the language it was sent in, and `MainActivity`'s UI text
   says so explicitly rather than implying cross-language capability that does not exist.
5. **`SessionService`'s BLE integration reuses `BleGattClientTransport`/`BleGattServerTransport`
   exactly as written 2026-09-26** — none of that session's own open items (reconnect/backoff,
   API 33+ characteristic-value overloads) were touched or newly investigated this session.
6. **No new manifest changes were needed** — every permission `SessionService`/`MainActivity` use
   (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, the BLE permission set) was already declared by the
   2026-09-26 session's manifest work; this session only added the runtime-request flow
   (`MainActivity.requestNeededPermissions()`) that was the one missing piece.
7. **Lint, not just compilation, is unverified.** The BLE call sites in `SessionService` carry
   `@Suppress("MissingPermission")` with a comment pointing at the runtime `BlePermissions` check
   that (in source, not verified by lint) makes each call site safe — Android Lint itself has
   never run against this code (same network blocker as `ktlintCheck`, see the
   "Authoring-environment limitations" section below).



Two pieces of real code written this session, in response to an external request to continue
implementing iTantra's Android integration end-to-end. Both are written to the same honesty
standard as the rest of this file (no simulated transport, no faked completion) — neither has
been compiled, because this session's sandbox has the exact same two structural constraints
every prior session documented: no Maven Central/Google Maven/Gradle-distribution network
access (blocks `wifi`, which needs `kotlinx-coroutines-core`), and no Android SDK at all (blocks
`android-app` and everything in it). Both facts were re-confirmed directly this session
(`curl -sI` against all four hosts, same `403 host_not_allowed` as every prior session).

1. **`WifiPacketTransportAdapter` (`wifi/src/main/kotlin/com/itantra/wifi/`)** resolves gap #1
   below: `orchestrator.MessageOrchestrator` needs `protocol.PacketTransport`
   (`send(frame: ByteArray)`, one already-fragmented packet at a time; reassembly is the
   orchestrator's own job), and `WifiUdpTransport`'s existing public API instead
   fragments/reassembles internally. The adapter bridges the two using two of
   `WifiUdpTransport`'s **existing, lower-level** primitives rather than its high-level ones:
   the new `WifiUdpTransport.sendFrame(frame: ByteArray)` (added alongside the adapter, one
   UDP datagram per already-encoded packet, no internal fragmentation) for outbound, and the
   pre-existing `receiveOnce()` (already returned raw per-fragment packets) driven in the
   adapter's own loop for inbound. `WifiUdpTransport.send()`/`incomingMessages`/`receiveLoop()`
   are unchanged and still correct for standalone (non-orchestrator) use — see the adapter's
   KDoc for why the two are alternative entry points, not meant to run together on one instance.
   **Not compiled** — same `kotlinx-coroutines-core` blocker as the rest of `wifi`.
2. **Real `android.bluetooth.*` GATT client/server (`android-app/src/main/kotlin/com/itantra/app/ble/`)**
   — §11's central ask, previously entirely unimplemented (`bluetooth.BleGattTransport` had only
   the Android-independent MTU/fragmentation/ack math, with explicit TODOs at every point
   needing real `BluetoothGatt`/`BluetoothGattServer` wiring, by deliberate prior-session
   choice — see that class's own KDoc). Four new files:
   - `ItantraGattProfile.kt` — the custom GATT service definition (one service, one
     central-writes-here characteristic, one peripheral-notifies-here characteristic, one
     optional status-read characteristic; UUIDs fixed and documented there).
   - `BleGattServerTransport.kt` — peripheral/server role: advertises, hosts a
     `BluetoothGattServer`, accepts writes, sends notifications. Implements `PacketTransport`.
   - `BleGattClientTransport.kt` — central/client role: scans, connects, negotiates MTU,
     subscribes to notifications, writes characteristics. Implements `PacketTransport`.
   - `BlePermissions.kt` — API-version-aware permission-check helper (does not itself launch a
     request flow — needs an `Activity`, which §5's Device Connection screen would provide and
     does not exist yet).
   Both transport classes delegate all MTU-aware sizing/negotiation-state/ack-window bookkeeping
   to the existing `bluetooth.BleGattTransport` rather than re-deriving any of that math — their
   own job is strictly the `android.bluetooth.*` plumbing that class was explicitly left without.
   **Not compiled, not run against real BLE hardware.** This is real, standard
   `BluetoothGattServer`/`BluetoothGattCallback`/scan API usage (not a simulation, not a stub),
   but GATT callback ordering and MTU-negotiation edge cases are genuinely OEM/Android-version-
   dependent — exactly the concern that stopped a prior session from writing this blind. What
   changed this session's judgment call: the request explicitly asked for this to be attempted
   now, unverified, clearly labeled as such (an accepted honest-completion path this project's
   own rules always allowed, just not previously exercised for this specific file). Each
   `TODO(needs a physical device)` in those two files marks a spot most likely to need
   adjustment once real hardware is available — a reconnect/backoff state machine and the
   API-33+ non-deprecated characteristic-value overloads are the two largest deferred items,
   deferred for the same "do not guess at undocumented device-specific behavior" reason
   `BleGattTransport`'s original KDoc gave.
   AndroidManifest.xml also gained the pre-Android-12 legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/
   `ACCESS_FINE_LOCATION` permissions (all `maxSdkVersion="30"`) alongside the already-present
   API 31+ set — the manifest previously only declared the API 31+ permissions despite
   `minSdk = 26` spanning both eras, which would have left BLE completely non-functional on
   any device running Android 8.0–11.
3. **New gap found, not fixed this session**: neither `BleGattServerTransport` nor
   `BleGattClientTransport` is wired into `SessionService` or any UI — they are complete,
   self-contained `PacketTransport` implementations with no caller yet. Wiring them in needs
   §5's Device Connection screen (to decide which device scans vs. advertises — see
   `BleGattClientTransport`'s KDoc on why that is a user choice, not an auto-negotiation) and a
   composition root in `ITantraApplication`/`SessionService`, both still Gate-1 shells. See
   `docs/PENDING_WORK_MASTER.md`, new P2.6/P2.7 entries.
4. **`WifiUdpTransport.configuredMtu`** (a new public read-only property) was added purely so
   `WifiPacketTransportAdapter.currentStats()` could report a real configured MTU instead of
   either hardcoding a second copy of the default or leaving the field wrong — no behavior
   change to `WifiUdpTransport` itself.


Things that are known to be incomplete, unverified, or wrong, recorded so
nobody re-discovers them from scratch. This is not a bug tracker for a
released product — it's the honest state of an in-progress research/
engineering system, per §46's Definition of Done ("a known limitation with
a number attached is more credible than silence").

## HEMIM + `:orchestrator` addition (2026-09-24) — supersedes nothing below, adds to it

Two new pure-Kotlin/JVM subsystems this session: `alert-engine`'s HEMIM classifier
(`docs/HEMIM.md`) and the new `:orchestrator` module (`docs/ORCHESTRATOR.md`). Both were
compiled with the same real `kotlinc-jvm 2.0.20` this file already documents below, and both
have real-executed test evidence (101/101 checks combined). New gaps found and precisely
documented, not silently patched over or ignored:

1. **`WifiUdpTransport` does not implement `protocol.PacketTransport`.** `MessageOrchestrator`
   depends on the latter (`send(frame: ByteArray)` — an already-framed single packet);
   `WifiUdpTransport.send(payload, langCode, messageType, isAlert)` takes an unframed
   application payload and fragments it internally. Wiring `:orchestrator` to real Wi-Fi
   needs an adapter or a small refactor — see `docs/PENDING_WORK_MASTER.md` P2.6.
2. **`WifiUdpTransport.send()` was refactored** to call the new shared
   `protocol.PacketFragmenter` instead of its own inlined fragmentation logic (removing
   duplication with `bluetooth.BleFragmenter`). Reviewed line-by-line for behavioral
   equivalence but **not recompiled** — `wifi` still cannot be compiled in this sandbox at
   all (see "Authoring-environment limitations" below; `kotlinx-coroutines` needs Maven
   Central). Verify with `./gradlew :wifi:compileDebugKotlin` before relying on it.
3. **No `SemanticPacket`<->wire-bytes serialization existed anywhere in the repository**
   before this session (confirmed by grep). `protocol.SemanticPacketWireCodec` is now that
   piece — real-executed, including truncated/corrupt-input rejection.
4. A real bug (not a documentation gap) was found and fixed via test execution: a
   delivery-status race where a synchronous transport's instant ACK round trip got clobbered
   by an unconditional status write. See `orchestrator/validation/RESULTS.md` for the full
   root-cause writeup — flagged here because the *class* of bug (assuming a transport is
   async when a fake/loopback one might not be) is worth remembering when a real transport
   is eventually wired in.
5. `android-app/SessionService.kt` was deliberately **not** touched this session — it is
   still an accurate empty shell. Wiring `:orchestrator` into it needs an Android SDK to
   compile/verify against, which this sandbox does not have; editing it blind, unverifiable,
   was judged worse than leaving the accurate shell in place. See the session's final
   handover report for the full reasoning.

## Independent audit + P4.5 fix (2026-09-23) — supersedes the compiler-toolchain claims below

An independent verification pass, run against this committed repository (not a fresh
merge), re-confirmed the 2026-09-18 merge session's factual claims by direct inspection
(grepping for `TODO`/`NotImplementedError`, reading `MainActivity.kt`,
`SimulationBackedSttEngine.kt`, `WifiUdpTransport.kt` directly, re-running the exact same
network probes) rather than trusting the prior docs at face value. All of it held up.

Two new things this session:

- **GitHub release assets are reachable even though Maven/Gradle infrastructure isn't.**
  `services.gradle.org`, `repo.maven.apache.org`, `dl.google.com`, and
  `plugins.gradle.org` are still all `403 host_not_allowed` (reconfirmed), but
  `github.com`/`codeload.github.com`/`release-assets.githubusercontent.com` are not, and
  JetBrains ships `kotlinc` itself as a GitHub release asset. This session downloaded the
  real Kotlin **2.0.20** compiler (the project's actual target version) this way and
  recompiled all 34 pure-JVM main-source files, unmodified, with zero errors — strictly
  better evidence than the prior sessions' ancient-apt-Kotlin-1.3.31-plus-manual-patches
  approach, though it still does not exercise Gradle, AGP, or dependency resolution at
  all. See `tests/build-logs/2026-09-23_real_kotlinc_2.0.20_main_compile.txt`. JUnit jars
  were not similarly pursued (Maven-Central-only, lower priority); this remains a gap.
- **P4.5 (real goodput accounting) implemented and merged.** See
  `docs/PENDING_WORK_MASTER.md` P4.5 and `wifi/validation/` for the fix and its isolated
  verification. This is the one functional code change made this session — everything
  else was audit/verification, per this session's own scope decision to prioritize honest
  re-verification over speculative new feature work on modules that need Android SDK or
  physical-device access this sandbox will never have.

## iTantra-Master merge (2026-09-18) — current status, supersedes the Gate-1-only sections below

This file's original content (everything below this section) described the state of the
`itantra-gate1.tar.gz` archive alone, before it was merged with three other archives into
this repository. Some of it is now stale — e.g. `SubwordSemanticCodec` no longer exists
(replaced by a real, working `BpeSemanticCodec`), `wifi`/`bluetooth` are no longer
Android-SDK-required modules, `PacketCodecTest.kt` no longer exists (replaced by
`ProtocolModuleTests.kt` against the new `PacketFormat.kt`). It's kept below for historical
record rather than deleted, but **do not treat it as the current state** — see
`docs/MASTER_INTEGRATION_AUDIT.md` and `docs/PENDING_WORK_MASTER.md` for that.

New issues found or introduced during the merge:

- **Same ancient-toolchain constraint, now with more evidence.** This session independently
  hit exactly the limitation described in the "Authoring-environment limitations" section
  below (apt's Kotlin 1.3.31 vs. the project's real 2.0.20 target) and used the same
  workaround methodology, extended to more stdlib-API differences (`sumOf`, `maxByOrNull`/
  `minByOrNull`, `String.lowercase()`, `kotlin.collections.ArrayDeque`, none of which exist
  in 1.3.31). See `docs/MASTER_INTEGRATION_AUDIT.md` §4 and `docs/DEVELOPMENT_HANDOFF.md`
  for exactly what was and wasn't validated this way. `./gradlew build` still has never
  been run against this codebase, merged or otherwise.
- **`wifi`'s test suite has never executed.** `kotlinx-coroutines-core`/`-test` aren't
  reachable in this sandbox (Maven Central isn't on the allow-list), so
  `WifiUdpTransportTest.kt` (a real 127.0.0.1 loopback test) was written but not compiled
  or run. Every other pure-JVM module's tests were at least compile-checked this session
  (see `DEVELOPMENT_HANDOFF.md`); this one wasn't even that.
- **`bluetooth` and `wifi` changed from Android-library to plain-JVM Gradle modules** during
  this merge (see `docs/FILE_CONFLICT_MATRIX.md`). This is a deliberate, documented
  architecture change, not an accident — but it means CI configuration that assumed those
  two modules needed the Android SDK to build is now stale and should be revisited once CI
  actually runs (`.github/workflows/ci.yml` was not modified this session to reflect it).
- **No translation component exists anywhere in the merged codebase.** This was true of
  every source archive individually and remains true after merging them — merging four
  archives that each lack translation does not produce translation. Flagged prominently
  here because it's the gap most likely to be silently assumed away; see
  `docs/PENDING_WORK_MASTER.md` P3.1 for the full explanation of why `LanguageRouter`/
  `LanguageRegistry` are routing, not translation.
- **`core/Contracts.kt`'s `SemanticPacket` no longer requires `tokenIds.isNotEmpty() ||
  residualPayload != null`** (the Gate1-git archive had this constraint; it was dropped —
  see `docs/FILE_CONFLICT_MATRIX.md` "core / contracts" row 1 for why). If any code
  written before this merge relied on that constructor-time guarantee, it no longer holds.
- **`docs/ARCHITECTURE.md`, `DECISIONS.md`, `BENCHMARKS.md`, and `handoff/HANDOFF_PACKAGE.md`
  were not rewritten this session** and still describe the pre-merge Gate-1-only state —
  see `docs/DEVELOPMENT_HANDOFF.md` "What's NOT done."

---

## Historical (Gate 1 only, pre-merge) — see the section above for current status

## Gate 1 scope limitations (expected, not bugs)

- **No model integration.** `stt`, `tts`, `language-id`'s auto-LID path,
  and `codec`'s `SubwordSemanticCodec` are all `NotImplementedError` stubs.
  This is intentional Gate 1 scope (§16) — model selection requires a
  filled Model Scorecard (§18) first, which requires benchmarking real
  candidates, which is Gate 2/4/9 work.
- **No transport implementation.** `wifi/WifiTransport` and
  `bluetooth/BleTransport` throw `NotImplementedError` on every method.
  Gate 5 (Wi-Fi loopback) and Gate 7 (BLE) implement these for real.
- **No Android UI or session wiring.** `android-app` has an empty
  `Application`, `Activity`, and foreground `Service` — no permissions
  flow, no DI graph, no binding between modules. Building this now, against
  stub dependencies, would produce nothing testable; it's deferred until at
  least the capture→VAD→endpointer leg has a real implementation to drive.

## Authoring-environment limitations (why some things are unverified locally)

This repository was scaffolded in a sandboxed environment with **no network
access to Maven Central, Google's Android SDK/Maven repo, or the Gradle
distribution server** — only a handful of domains (GitHub, PyPI, npm,
Ubuntu archives) are reachable. This shaped what could and couldn't be
verified before this first commit:

- **Local Gradle/Kotlin toolchain is ancient.** `apt-get install gradle
  kotlin` in this sandbox pulls Gradle 4.4.1 and Kotlin 1.3.31 — both far
  older than the versions this project actually targets (Gradle 8.9, Kotlin
  2.0.20, per the wrapper and `build.gradle.kts`). Running `./gradlew build`
  in this sandbox would fail on toolchain-version grounds, not code
  correctness grounds, so it wasn't attempted as evidence of anything.
- **What was actually verified**: every pure-Kotlin/JVM module's main
  source (`core`, `protocol`, `vad`, `codec`, `benchmark`) was compiled
  directly with the sandbox's `kotlinc`, using a scratch copy with two
  Kotlin-1.9+-only constructs (`enum.entries`, `data object`) mechanically
  substituted for older equivalents (`values()`, `object`) purely to work
  around the ancient local compiler — the committed source keeps the modern
  syntax. Beyond compiling, each of those four modules also has a
  hand-written smoke-test `main()` (not committed — run ad hoc during
  scaffolding) that exercised real logic: packet round-trip + a 20,000-
  iteration fuzz pass with zero crashes (`protocol`), silence-vs-tone
  classification and noise-floor adaptation (`vad`), multi-byte Hindi/Tamil
  UTF-8 round-trip (`codec`), and JSON escaping correctness (`benchmark`).
  This is real functional evidence, but it is **not the same as a CI run on
  the actual target toolchain** — treat it as "logic is sound," not "this
  will compile and pass under Gradle 8.9/AGP 8.5/Kotlin 2.0.20 without
  issue." The first real signal on that front comes from `.github/workflows/ci.yml`
  actually running on GitHub Actions, which has full network access.
- **Android modules (`android-app`, `audio`, `alert-engine`, `wifi`,
  `bluetooth`) are entirely unverified locally.** No Android SDK is
  available in this sandbox. Their `build.gradle.kts` files and Kotlin
  sources were written carefully against known-correct AGP/Android API
  patterns, but the first real compile check happens in CI.
- **The three `tools/*.py` scripts (loss-injection relay, benchmark-bundle,
  model-scorecard) were run and verified for real**, including a 200-packet
  loopback test with measured loss rate matching the configured target, a
  synthetic-data aggregation run, and a validate-fails-then-validate-passes
  round trip. See each script's module for what was tested.
- **ktlint is wired into the root build but its `ktlintCheck` task has
  never actually run** (it needs the plugin's dependency from
  `plugins.gradle.org`, unreachable here). It's plausible the current
  formatting doesn't fully satisfy ktlint's default ruleset — CI's
  `static-analysis` job is the first real check.

## Things to double check once real tooling is available

- [ ] Run `./gradlew build` for real on a machine/CI with full network and
      confirm every module actually compiles under Gradle 8.9/AGP 8.5.2/
      Kotlin 2.0.20 — the version pins in the Gradle files have not been
      tested against each other for compatibility, only chosen as
      current-as-of-training-data reasonable versions.
- [ ] Confirm `android-actions/setup-android@v3` (used in `ci.yml`) is
      still the current recommended action — verify against its repo
      before relying on it long-term.
- [ ] Run `ktlintCheck` and fix whatever it flags.
- [x] Promote the protocol fuzz pass into a committed test — done:
      `protocol/src/test/kotlin/.../PacketCodecTest.kt`'s
      `random garbage input never throws` test (5000 bounded iterations,
      kept lower than the original 20000 to keep CI fast).
