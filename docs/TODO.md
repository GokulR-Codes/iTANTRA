# TODO.md

Gate-by-gate plan. Exit criteria below are §16 of the master prompt,
copied verbatim/near-verbatim, not paraphrased into something laxer —
a gate isn't done until its stated exit criterion is met (§46's
"Implemented" definition), not when it feels roughly done.

## Gate 1 — Repository & architecture — **IN PROGRESS**

Exit criteria: *Repo matches §15; empty-but-compiling modules for all four
divisions; CI runs a no-op build; `DECISIONS.md` entry #1 records ingestion
of this document.*

- [x] Repo structure matches §15
- [x] All four divisions have modules (`vad`/`language-id`/`stt`/`tts` = A,
      `codec` = B, `android-app`/`audio`/`alert-engine` = C,
      `protocol`/`wifi`/`bluetooth` = D)
- [x] `DECISIONS.md` entry #1 written, with date and Class L summary
- [x] Pure-JVM modules (`core`, `protocol`, `vad`, `codec`, `benchmark`)
      functionally verified locally (see `docs/KNOWN_ISSUES.md` for method
      and caveats)
- [ ] **CI actually runs green on GitHub Actions** — `.github/workflows/ci.yml`
      is written but has never executed; this sandbox has no path to run it.
      This is the one remaining item standing between "should close Gate 1"
      and "closes Gate 1" per §46's Definition of Done (code existing isn't
      enough — the check has to have actually run).

## Gate 2 — Single-language local speech loop — NOT STARTED

Exit criteria: *Speak → STT text visible in UI → TTS speaks it back, no
network, on a real Class L or emulated-L device.*

Needs, in rough order: a Model Scorecard (§18) for one STT + one TTS model
(likely English or Hindi first), real `AudioCapture`/`AudioPlayback` in
`audio/`, wiring `SttEngine`/`TtsEngine` real implementations behind the
existing interfaces, and a minimal UI in `android-app`.

## Gate 3 — Streaming STT/TTS — NOT STARTED
Exit: partial hypotheses stream to UI during speech; TTS reports
first-audio latency; P50/P95 logged (§34).

## Gate 4 — Semantic representation — NOT STARTED
Exit: tokenizer + compact IDs implemented; bits/sec vs. UTF-8 measured on a
real corpus (§35). `codec/Utf8BaselineCodec` (done, Gate 1) is the
required comparison baseline this measurement runs against.

## Gate 5 — Wi-Fi communication — NOT STARTED
Exit: loopback test — packetize → send over local socket → reassemble,
CRC validated, on one device. `protocol/PacketCodec` (done, Gate 1) is the
framing this test exercises; `wifi/WifiTransport` needs a real
implementation.

## Gate 6 — Two-phone communication — NOT STARTED
Exit: two physical (or two emulated + one physical) Android devices
exchange semantic packets over Wi-Fi, end to end, speech-to-speech.

## Gate 7 — BLE — NOT STARTED
Exit: same two-phone test over BLE GATT, with MTU negotiation and
fragmentation working. `bluetooth/BleTransport` needs a real
implementation — note its `currentStats()` deliberately returns
`mtuBytes = 0` today rather than a hardcoded guess (§26 explicitly forbids
hardcoding BLE MTU).

## Gate 8 — Packet reliability — NOT STARTED
Exit: 0/5/10/20% random + burst + reorder + duplication + corruption tests
all pass with graceful semantic-survives-first behavior (§36).
`tools/loss_injection.py` (done, Gate 1) is built for exactly this test.

## Gate 9 — Multilingual expansion — NOT STARTED
Exit: each of the 10 languages has a passing validation record (§21) —
checksum, license, at least a smoke-test WER number. `core/Language` (done,
Gate 1) enumerates all 10 codes already.

## Gate 10 — Low-end optimization — NOT STARTED
Exit: full core loop demonstrated on a genuine Class L device (not just
Class H) within the RAM/CPU envelope (§29).

## Gate 11 — Benchmark automation — NOT STARTED
Exit: one-click benchmark bundle produces WER/CER/RTF/P50/P95/bitrate/CPU/
RAM with device+model+config hashes (§32).
`benchmark/TelemetryEvent` + `tools/benchmark_bundle.py` (done, Gate 1) are
the schema and aggregator this gate builds the "one-click" collection
pipeline around.

## Gate 12 — Neural enhancement — NOT STARTED
Exit: ablation study exists; residual is either kept with measured benefit
or explicitly removed from the production path (§23).

## Gate 13 — Security — NOT STARTED
Exit: mutual auth, encryption, replay protection all implemented;
fuzz-tested parser has zero crashes over a defined corpus (§30).
`protocol/PacketCodec` already fuzz-tests malformed/truncated frames
(Gate 1, 5000-iteration committed test) — Gate 13 adds the crypto layer
`docs/ARCHITECTURE.md` explicitly notes is *not* in `protocol/` yet.

## Gate 14 — Stress testing — NOT STARTED
Exit: 30–60 minute continuous run with logged CPU/RSS/thermal/battery
drift and crash/reconnect count (§37).

## Gate 15 — Grand Finale readiness — NOT STARTED
Exit: Tier 1/2/3 all demoed live; evidence package complete (§40);
judge-attack rehearsal completed (§44).

---

## Non-gated follow-ups noted during Gate 1

- [ ] Validate CI's `android-actions/setup-android@v3` and Gradle/AGP/Kotlin
      version pins actually work together (see `docs/KNOWN_ISSUES.md`)
- [ ] Run `ktlintCheck` for real and fix whatever it flags
- [ ] Pick and validate the actual Class L reference device; update the
      `[ASSUMPTION, ENGINEERING]` minSdk=26 note in `docs/ARCHITECTURE.md`
      once that's real rather than assumed
