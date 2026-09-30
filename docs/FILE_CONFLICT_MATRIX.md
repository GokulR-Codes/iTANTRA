# FILE CONFLICT MATRIX — iTantra-Master

Every file that existed in more than one source archive with materially different content,
or that collided by name/type with another file during the merge, is listed below. Files
that were identical or near-identical across archives (verified with `diff`, not assumed)
are not listed — see `docs/MASTER_INTEGRATION_AUDIT.md` §1 for the one confirmed example
(`docs/*.md` between archives 1 and 2, which were the same snapshot two days apart with
only `docs/DECISIONS.md`'s date differing).

Legend for **Archive**: G1S = `iTantra_Gate1_Skeleton.zip`, G1T = `itantra-gate1.tar.gz`,
P3 = `iTantra_PartIII_SpeechLanguageStack.zip`, P4 = `iTantra-PartIV.zip` (nested in
`files__12_.zip`).

---

### `core` / contracts

| Concept | Archives with a version | Selected | Reason |
|---|---|---|---|
| `AudioFrame`, `RecognizedUtterance`, `Packet`(old)/`SemanticPacket`, `ReceivedUtterance`, `SynthesizedAudio`(core copy), `BenchmarkEvent` | G1S (split files), G1T (`Contracts.kt`), P3 (`Contracts.kt`), P4 (`Contracts.kt`) | **New merged `Contracts.kt`** | G1T was the only copy with `equals`/`hashCode` overridden on the `ShortArray`/`IntArray`-bearing classes (G1S and P3 both had a live equality bug: silent reference equality). Kept G1T's overrides, P3/P4's richer `ChannelStats`/`ThermalState` fields, and G1S's `EndpointTiming`/`SessionMode`. `SemanticPacket`'s G1T-only `require(tokenIds.isNotEmpty() \|\| residualPayload != null)` was **dropped** — P4's real codec legitimately produces empty-tokenIds packets (e.g. a confidence-0 decode edge case) and G1T's own constraint would have rejected that. |
| `ChannelStats` | G1T (Double, `lossFraction` 0-1, has `transport`+`selectedMode`) vs. G1S/P3/P4 (Float, `lossPct` 0-100, has `queueDepth`/`thermalState`/`batteryPct`, no `transport`/`selectedMode`) | **Majority (G1S/P3/P4) shape** | 3 of 4 archives agreed, and it's the architecturally correct choice: `selectedMode` is the *output* of `codec.AdaptiveChannelController.update(stats)`, not an input field — storing it on the stats themselves (G1T's design) was circular. This shape also matches what P4's real `WifiUdpTransport.currentChannelStats()` already constructs, minimizing changes to validated code. |
| `ThermalState` | G1S (6 values incl. UNKNOWN) vs. P3 (5 values, no UNKNOWN) vs. P4 (4 values, non-Android-matching names WARM/THROTTLING) | **G1S's 6-value version** | Most faithful mirror of Android's real `PowerManager` thermal-status granularity; has a defensive UNKNOWN fallback the others lack. `codec/ChannelController.kt`'s thermal hard-gate check was adapted from checking `THROTTLING`/`CRITICAL` to `SEVERE`/`CRITICAL`. |
| `Language` vs `LanguageCode` | G1S/G1T (`Language`, long names: HINDI, GUJARATI, ...) vs. P3 (`LanguageCode`, short codes: HI, GU, ...) | **Merged: name `Language`, short-code entries** | The type name `Language` matches every architecture doc's prose; the short-code entry style matches the larger, already-tested body of code (`LanguageRegistry`, `TenLanguageMatrix`, `LanguageRouter`, `DomainLexicon`) that was built and unit-tested against P3's `LanguageCode`. Every reference to `LanguageCode` in the ported files was mechanically renamed to `Language` with no logic change (verified: only the type name changed, not field access patterns). |
| `EndpointState` + FSM engine | G1T (`core.EndpointStateMachine`, full logic in `core`) vs. P3 (`vad.Endpointer`, states in a locally-declared enum) | **States → `core.EndpointState` (shared vocabulary); engine → `vad.Endpointer` (kept, richer)** | G1S's own architecture doc for this FSM explicitly says the states are shared vocabulary in `core` but the transition logic belongs in `vad` — G1T's implementation didn't follow that split, P3's did (just with a locally-duplicated enum instead of importing a shared one). `core.EndpointStateMachine` (G1T) is **deleted**: `vad.Endpointer` (P3) has pre-roll tracking, a speech-confirm debounce, and external-signal-driven finalization that G1T's version lacked, and it's the version `SimulatedStreamingSttAdapter` was actually built and tested against. |
| `core.registry.*` (`LanguageRegistry`, `LanguagePackManifest`, `TenLanguageMatrix`) | P3 only | **Adopted as-is (renamed `LanguageCode`→`Language`)** | No competing implementation existed; purely additive. |
| `core.metrics.LatencyStats` | P3 only | **Adopted as-is** | No competing implementation; used by both `tts.TtsLatencyTracker` and this integration's own test suites. |

### `vad`

| File | Archives | Selected | Reason |
|---|---|---|---|
| `VadGate.kt` (`EnergyGate`, `NeuralVadGate` stub) | G1T only | **Deleted, superseded** | P3's `EnergyGateVad`/`NeuralVadPort`/`TwoStageVad` return a richer `VadResult` (probability + energy, not just boolean) and are the design the rest of P3's speech stack was actually validated against (58/58 checks in `tests/unit/run-logs/2026-09-05_PartIIICoreLogicTests.log`). |
| `EnergyGateVad.kt`, `NeuralVadPort.kt`, `TwoStageVad.kt`, `VadTypes.kt` | P3 only | **Adopted as-is** | No competing implementation once `VadGate.kt` was superseded. |
| `Endpointer.kt` | P3 only (see `EndpointState` row above for the G1T conflict) | **Adopted, adapted to import `core.EndpointState` instead of a local duplicate** | Single-source-of-truth fix. |

### `language-id`

| File | Archives | Selected | Reason |
|---|---|---|---|
| Package name | G1T used `com.itantra.langid`; P3 (and G1S's own original module doc) used `com.itantra.languageid` | **`com.itantra.languageid`** | P3's package matches G1S's *original* module documentation (`LanguageIdModule.kt` in G1S is itself `package com.itantra.languageid`) — G1T's `langid` was the actual outlier, not P3. |
| `LanguageRouter.kt` | G1T (`Language`-based, simple) vs. P3 (`LanguageCode`-based, has confidence-gated LID with `offerLidResult`) | **P3's version** (renamed to `Language`) | Materially more complete — confidence-floor gating against false LID switches, explicit `lidEnabled` toggle. |
| `LanguageIdentifier.kt` | G1T (nested interface inside `LanguageRouter.kt`, `identify(recentSpeech: AudioFrame): LidGuess?`) vs. P3 (standalone file, `identify(frames: List<AudioFrame>): LidResult`, plus an honest `UnimplementedLanguageIdentifier`) | **P3's version** | More complete, includes the honest stub; G1T's nested interface had no implementation at all, stub or otherwise. |

### `stt`

| File | Archives | Selected | Reason |
|---|---|---|---|
| `SttEngine` interface | G1T (`activeLanguage`, `onFrame`, `lastRtf`, `loadLanguage`/`unloadLanguage`) vs. P3 (`acceptFrame`/`finalize`/`reset`, no model-lifecycle methods) — genuinely incompatible signatures | **G1T's interface kept as canonical** | Models per-language load/unload, which `LanguageRegistry`'s one-resident-language invariant (§29) genuinely needs; P3's version had no way to express that at all. |
| `SimulatedStreamingSttAdapter.kt`, `DomainLexicon.kt` | P3 only | **Adopted as-is** (renamed `LanguageCode`→`Language` in `DomainLexicon`) | `SimulatedStreamingSttAdapter` doesn't implement *either* `SttEngine` interface on its own (it has a third, narrower signature: `onFrame(frame, vadIsSpeech): RecognizedUtterance?`) — see next row for how this was resolved. |
| Bridge between the two designs | Neither archive had one | **New file: `SimulationBackedSttEngine.kt`** | Wraps P3's validated buffering/endpointing adapter so it can be plugged in anywhere a real `SttEngine` (G1T's interface) is expected, for local pipeline wiring/demos. Defaults to a `SilentPlaceholderRecognizer` that returns an explicit `"[simulated-stt: ...]"` marker string and zero confidence — it cannot produce a plausible-looking fabricated transcript, by construction. |

### `tts`

| File | Archives | Selected | Reason |
|---|---|---|---|
| `SynthesizedAudio` | `core.Contracts` copy (`isAlert`, `firstAudioReadyTsMs`) vs. a second, locally-declared copy inside `tts/TtsEngine.kt` (G1T) with a different field set (`durationMs`, `firstAudioMs`) | **`core`'s copy is canonical; `durationMs` folded into it as an additive field** | Exact class-name collision across modules — a textbook Single-Source-of-Truth violation. `core`'s absolute-timestamp style (`firstAudioReadyTsMs`) matches every other contract's timestamp convention; `tts`'s only genuinely new field (`durationMs`, total synthesized length) had no equivalent in `core`'s copy and was added there rather than lost. |
| `TtsEngine` interface | G1T (`activeLanguage`, `synthesize(text, isAlert)`, `loadLanguage`/`unloadLanguage`) vs. P3 (`synthesize(normalizedText, languageIso, isAlert)`, no lifecycle methods) | **G1T's interface kept** | Same model-lifecycle reasoning as `SttEngine` above. |
| `TextFrontEnd.kt`, `TtsLatencyTracker.kt` | P3 only | **Adopted as-is** | Purely additive, no conflict; `TtsEngine.synthesize()`'s doc now points callers at `TextFrontEnd.normalize()`. |

### `codec`

| File | Archives | Selected | Reason |
|---|---|---|---|
| `SemanticCodec` — name collision: an interface in G1T's `codec` module vs. a concrete class of the same name in P4's `codec` module | G1T (interface, `decode(): String`) vs. P4 (class, `decode(): ReceivedUtterance`, internal `AlertLexicon`-based priority auto-detection) | **Interface kept (canonical), `decode()` return type upgraded to `ReceivedUtterance`; P4's class renamed to `BpeSemanticCodec` and adapted to the interface's explicit-priority-parameter signature** | `ReceivedUtterance` (confidence + reconstructed-from-residual info) is genuinely needed downstream and was already what P4's real implementation computed; returning a bare `String` (G1T's original) would have thrown that away. The interface's `encode(utterance, priority, ttlMs, sentenceId)` shape (caller-supplied priority) was kept over P4's internal-auto-detection shape so `Utf8BaselineCodec` and `BpeSemanticCodec` share one signature; P4's auto-detection logic survives as the standalone, reusable `AlertLexicon.priorityFor()` policy helper. |
| `SubwordSemanticCodec` (G1T, `NotImplementedError` stub for "no trained tokenizer exists yet") | G1T only | **Deleted, superseded** | P4's `BpeSemanticCodec` is a real, working, measured implementation of exactly what this stub was a placeholder for. Keeping both would misrepresent the stub as still-relevant. |
| `Tokenizer.kt`, `EntropyCoder.kt`, `NeuralResidual.kt` | P4 only | **Adopted, package fixed `itantra.codec`→`com.itantra.codec`** | No competing implementation. |
| `ChannelController.kt` (`AdaptiveChannelController`, and a locally-declared duplicate `enum class ChannelMode`) | P4 only, but its local `ChannelMode` duplicated `core.Contracts.ChannelMode` (already established by G1T) | **Adopted; local `ChannelMode` duplicate deleted, imports `core.ChannelMode` instead** | Single-source-of-truth fix. Also adapted the thermal hard-gate check from P4's own 4-value `ThermalState` names (`THROTTLING`) to the merged 6-value enum's `SEVERE`/`CRITICAL`. |
| `AlertLexicon` | P4 only (originally embedded inside its `SemanticCodec.kt`) | **Split into its own file, `AlertLexicon.kt`** | Separates alert-detection policy from codec mechanism, so it's reusable independently of which codec implementation is active. |

### `protocol`

| File | Archives | Selected | Reason |
|---|---|---|---|
| `Packet.kt` (G1T) vs. `PacketFormat.kt`/`Crc16.kt`/`MessageType.kt` (P4) — two non-interoperable wire formats: different magic nibble (`0x1<v>` vs `0xA<v>`), different decode API (sealed-result vs. exception-based) | G1T vs. P4 | **P4's format adopted as canonical; G1T's `Packet.kt` deleted** | P4's version has a documented, real finding (the `timestampMs` wire-truncation bug, found by executing the Python reference against a realistic Unix-epoch value) that G1T's version doesn't address at all, plus a cleaner API (`encode()`/`decode()` as members of `Packet` itself) and explicit forward-compatible reserved-bit handling. Package fixed `itantra.protocol`→`com.itantra.protocol`. |
| `PacketTransport.kt` (transport-agnostic send/receive interface) | G1T only | **Kept as-is** | No conflict — operates on raw `ByteArray`, independent of which `Packet` format is in use underneath. |
| `ReliabilityLayer.kt` (`ReplayWindow`, `RetransmissionQueue`, `SelectiveAckTracker`) | P4 only | **Adopted, package fixed** | No competing implementation. |
| `FragmentReassembler.kt` | Neither archive had one | **New file, written during this integration** | The master integration prompt explicitly predicted this gap ("Kotlin fragment reassembler: Likely missing or incomplete") and it was confirmed true: P4's `WifiUdpTransport.send()` fragments outbound payloads, but nothing on the receive side ever reassembled them — `receiveOnce()` returned raw per-fragment `Packet`s with no bookkeeping across calls. |

### `wifi`

| File | Archives | Selected | Reason |
|---|---|---|---|
| Module type (Android library vs. plain JVM) | G1T (`com.android.library`, `UnimplementedWifiTransport` stub only) vs. P4 (plain Kotlin/JVM, real `WifiUdpTransport`) | **Plain Kotlin/JVM (P4's posture)** | `DatagramSocket` is plain `java.net`, not Android-specific — P4's own build file already modeled it this way. This lets `wifi`'s real logic be unit-tested in plain CI (and in this integration's own sandbox), rather than requiring a device/emulator just to test UDP fragmentation math. |
| `WifiTransport.kt`/`WifiUdpTransport.kt` | G1T (stub) vs. P4 (real, UDP-loopback-validated) | **P4's version adopted, package fixed, `FragmentReassembler` wired into a new `receiveLoop()`** | P4's `incomingPackets: Flow<Packet>` was declared but nothing ever emitted into it, and nothing reassembled fragments — the same gap `FragmentReassembler.kt` fixes at the protocol layer. `receiveLoop()` + `incomingMessages: Flow<ReassembledMessage>` is new code written to close that gap, not present in any source archive. |

### `bluetooth`

| File | Archives | Selected | Reason |
|---|---|---|---|
| Module type | G1T (`com.android.library`, stub only) vs. P4 (plain Kotlin/JVM, real MTU/ACK logic, but zero `android.bluetooth.*` usage) | **Plain Kotlin/JVM for now** (documented as needing to become an Android library module once real `BluetoothGatt` wiring is added) | Nothing in P4's BLE code touches Android APIs yet; keeping it JVM lets its real logic (MTU math, windowed ACK) be unit-tested today. |
| `BleTransport.kt` | G1T (stub) vs. P4 (real `BleFragmenter`, `WindowedAckTracker`, `BleGattTransport`, the `MINIMUM_FUNCTIONAL_MTU` finding) | **P4's version adopted, package fixed** | Substantially more developed; the `MINIMUM_FUNCTIONAL_MTU` finding (a negotiated ATT_MTU at the BLE 4.0 spec floor of 23 cannot carry even one byte of this project's wire format) is a genuine, load-bearing architectural finding worth preserving prominently. |
| Receive-side reassembly hook | Neither archive had one for BLE | **New: `BleGattTransport.onFragmentReceived()`**, added during this integration | Forward-looking use of the same `FragmentReassembler` the wifi module now uses, ready for the day real `BluetoothGattCallback.onCharacteristicChanged` wiring exists — not yet called from anywhere, since that wiring doesn't exist. |

### `audio`, `alert-engine`

| File | Issue | Fix |
|---|---|---|
| `AudioIo.kt`, `AlertEngine.kt` | Both imported `com.itantra.tts.SynthesizedAudio`, which was deleted in the `core`/`tts` merge (see `tts` section above) | Imports fixed to `com.itantra.core.SynthesizedAudio`. `alert-engine`'s now-unnecessary `implementation(project(":tts"))` Gradle dependency removed. |

### `docs/`, root Gradle files, CI

| Item | Archives | Selected | Reason |
|---|---|---|---|
| `docs/ARCHITECTURE.md`, `BENCHMARKS.md`, `KNOWN_ISSUES.md`, `TODO.md`, `DECISIONS.md`, `docs/handoff/HANDOFF_PACKAGE.md`, root `README.md` | G1S vs. G1T | **G1T's versions** (byte-identical to G1S's except `DECISIONS.md`'s date, confirmed with `diff`) | G1T is strictly the later, more complete snapshot. |
| `settings.gradle.kts`, root `build.gradle.kts`, `gradle-wrapper.jar`, CI workflows | G1S (no real wrapper jar committed, `.github/workflows/ci.yml` only) vs. G1T (real wrapper jar, `ci.yml` + `scheduled-device-tests.yml`) | **G1T's versions** | G1T has an actually-committed, real Gradle wrapper JAR; G1S's `settings.gradle.kts` was also missing the `benchmark` module declaration that G1T has. |
