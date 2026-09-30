# BENCHMARKS.md

Every number in this file must be `[MEASURED]` (§1) — produced by actually
running something and recording the result, with enough context (device,
date, git SHA, model/config hash where relevant) to reproduce it. No
estimated, assumed, or "should be roughly" numbers belong here; those go in
prose elsewhere clearly marked `[TARGET]` or `[ASSUMPTION]`, never here.

**Status: empty.** No entry below yet, because no measurement has been
taken on a real or even emulated device — Gate 1 is scaffolding and
interface work. The `tools/loss_injection/tests/test_loopback.py`,
`protocol`'s packet round-trip/fuzz tests, and the other module smoke tests
described in `docs/KNOWN_ISSUES.md` are correctness checks, not
performance benchmarks, and are not reported here for that reason — they
belong in each module's own test suite, which they already are.

## How entries get added

Once `tools/benchmark_bundle/benchmark_bundle.py` has real telemetry to
aggregate (from a real device via `benchmark/JsonlTelemetryWriter`), its
output — one markdown table per device-class/transport combination — gets
appended below, along with:

- Date and git SHA the measurement was taken at
- Device (real model, or "emulated-L" with the emulator's configured
  RAM/CPU profile)
- Model/config hash (§40) for anything model-dependent
- A one-line note on what changed since the last entry for that metric, if
  applicable, so a regression or improvement is traceable to a commit

## Template for the first real entry

```
## <date> — Gate <N> — <what was measured>

Device: <real device model | emulated-L, profile: X GB RAM, Y cores>
Git SHA: <sha>
Config/model hash: <hash, if applicable>

<benchmark_bundle.py output table>

Notes: <anything that affects interpretation — thermal state, background
load, sample size caveats, etc.>
```
