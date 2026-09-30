# ARCHITECTURE.md

Current state of the system, kept in sync with the code — if this
disagrees with what's actually in the repo, this file is stale and should
be fixed, not treated as the source of truth (the code is). Governing
spec: `iTantra_SIH26173_Master_Development_Prompt.md` (see `docs/DECISIONS.md`
entry #1); `§N` below refers to that document's sections.

## Module map

```
                        ┌─────────────┐
                        │    core     │  contracts + state machines
                        │ (pure JVM)  │  (AudioFrame, SemanticPacket, ChannelStats,
                        └──────┬──────┘   EndpointStateMachine, Language, DeviceClass)
                               │
       ┌───────────┬───────────┼───────────┬────────────┐
       │           │           │            │            │
   ┌───▼───┐   ┌───▼────┐  ┌───▼───┐   ┌────▼────┐  ┌────▼─────┐
   │  vad  │   │language│  │  stt  │   │   tts   │  │  codec   │
   │(JVM)  │   │  -id   │  │ (JVM) │   │  (JVM)  │  │  (JVM)   │
   └───────┘   │ (JVM)  │  └───────┘   └─────────┘  └────┬─────┘
               └────────┘                                 │
                                                       ┌────▼─────┐
                                                       │ protocol │  PacketCodec, Crc16,
                                                       │  (JVM)   │  PacketTransport (interface)
                                                       └────┬─────┘
                                        ┌───────────────────┼───────────────────┐
                                        │                                       │
                                   ┌────▼────┐                            ┌─────▼────┐
                                   │  wifi   │  (Android)                 │ bluetooth│  (Android)
                                   └─────────┘                            └──────────┘

   ┌───────┐   ┌─────────────┐   ┌────────────┐
   │ audio │   │ alert-engine│   │ android-app│   all Android; android-app depends on
   │(Andr.)│   │  (Android)  │   │(application)│   every module above (see its build.gradle.kts)
   └───────┘   └─────────────┘   └────────────┘

   ┌───────────┐
   │ benchmark │  telemetry event schema + JSONL writer (pure JVM)
   └───────────┘
```

Division ownership (§13):
- **Division A** (Speech Intelligence): `vad`, `language-id`, `stt`, `tts`
- **Division B** (Semantic & Neural Communication): `codec`
- **Division C** (Android Edge Application): `android-app`, `audio`, `alert-engine`
- **Division D** (Networking/Reliability/Security/Infra): `protocol`, `wifi`, `bluetooth`
- **Shared / Integration-QC owned**: `core`, `benchmark`

## Why the pure-JVM / Android split

`core`, `vad`, `language-id`, `stt`, `tts`, `codec`, `protocol`, and
`benchmark` are plain Kotlin/JVM modules — no Android dependency, even
though several will eventually be used from Android code. This means they
compile and unit-test on any JVM, in plain CI, without an emulator (§38).
`audio`, `alert-engine`, `wifi`, `bluetooth`, and `android-app` are Android
modules because they call framework APIs with no JVM equivalent
(`AudioRecord`, `BluetoothGatt`, `WifiP2pManager`, `NotificationManager`).
See `docs/DECISIONS.md` entry #4.

## What's real vs. stub right now (Gate 1)

**Real, implemented, tested:**
- `core`: all contract types + `EndpointStateMachine`
- `protocol`: `PacketCodec`/`Crc16` (full §24 framing, fail-closed, fuzz-tested), `PacketTransport` interface
- `vad`: `EnergyGate` (Stage 1, §19)
- `codec`: `Utf8BaselineCodec` (the mandatory §35 comparison baseline)
- `benchmark`: `TelemetryEvent` + `JsonlTelemetryWriter`
- `tools/`: `loss_injection.py`, `benchmark_bundle.py`, `model_scorecard.py` — all runnable, all tested

**Interface-only stubs (throw `NotImplementedError`, no fake data):**
- `stt`, `tts`: `SttEngine`/`TtsEngine` — need a Model Scorecard (§18) before real implementation
- `vad`: `NeuralVadGate` (Stage 2) — needs an ONNX-backed Silero-class model
- `codec`: `SubwordSemanticCodec` — needs a trained per-language tokenizer
- `wifi`, `bluetooth`: transport adapters — Gate 5/7 work
- `audio`, `alert-engine`: Android I/O — Gate 2/6 work
- `android-app`: empty `Application`/`Activity`/`Service` shell — wired for real once dependencies stop being stubs

Full detail and *why* each is a stub (not just "not done"): `docs/KNOWN_ISSUES.md`.

## Device classes (§29)

| Class | Reference profile | Required behavior |
|---|---|---|
| L — minimum | 2–4 GB RAM, CPU-only fallback | Core semantic path works with exactly one active language pack loaded |
| M — target | 4–6 GB RAM, optional NNAPI/GPU delegate | Full semantic path; residual only if measured to help without breaking Class L gates |
| H — development | High-end reference device | Training/benchmarking only — must never hide a Class L failure |

`[ASSUMPTION, ENGINEERING]`: Android API 26 (Oreo) as the `minSdk` floor
across all Android modules. Not sourced from the master prompt directly —
chosen as a reasonable floor for "low/mid-range Android hardware" (§29,
NFR2) pending validation against the actual Class L reference device once
one is chosen. Revisit and record the outcome in `docs/DECISIONS.md` when
that device is picked.

## Wire protocol (§24) — implemented in `protocol/Packet.kt`

`[24-byte header][variable payload][2-byte CRC-16 trailer]`. See
`docs/DECISIONS.md` entry #2 for how this resolves the source document's
24-vs-26-byte inconsistency.

## Roadmap

See `docs/TODO.md` for the gate-by-gate plan and `§16` of the master
prompt for the authoritative gate definitions and exit criteria.
