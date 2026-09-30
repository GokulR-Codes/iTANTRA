# Adaptive Channel Controller — Validation Results

**Status:** `[MEASURED]` (behavioral correctness of the state machine,
not real-world radio behavior). **Command:** `python3
channel_controller_reference.py`. **Output:** `channel_controller_output.txt`.
**Date:** 2026-09-05

## Result

```
channel_controller_reference.py — 6/6 checks passed
```

All six scenarios passed on the first execution after the earlier
`order` dict scoping cleanup (a pure refactor, not a logic fix). Confirmed
behaviors:

1. A consistently excellent link climbs `SEMANTIC_SAFE → SEMANTIC_PLUS_
   RESIDUAL → HIGH_FIDELITY_RESIDUAL` within 15 samples and never
   regresses while conditions stay excellent.
2. A single bad sample arriving right after reaching
   `HIGH_FIDELITY_RESIDUAL` steps the mode down **exactly one level**
   (to `SEMANTIC_PLUS_RESIDUAL`), not straight to `SEMANTIC_SAFE` — the
   EWMA absorbs the one-sample shock rather than reacting to it directly,
   which is the whole point of §27's smoothing requirement.
3. Thermal `THROTTLING`/`CRITICAL` forces `SEMANTIC_SAFE` immediately,
   overriding an otherwise-excellent link — this is the hard gate for
   Class L thermal safety (§29), not part of the smoothed hysteresis
   path.
4. Battery ≤10% forces `SEMANTIC_SAFE` the same way.
5. A noisy-but-good-on-average link (Gaussian loss noise, seed=42, mean
   1.5%, σ=2.0%) produced only **1 mode transition in 39 updates** —
   evidence the hysteresis gap (upgrade threshold 3% vs. downgrade
   threshold 8%) actually suppresses thrashing on this synthetic noise
   profile, not just in theory.

## What this does NOT prove

- **The threshold values themselves are `[TARGET]`, not `[MEASURED]`** —
  8%/3% loss and 400ms/150ms RTT were chosen as reasonable starting
  envelopes, not derived from real Wi-Fi/BLE measurements. §27 explicitly
  requires validating these against §36's real packet-loss test matrix
  (0/5/10/20% + burst/reorder/duplication/corruption) before shipping
  them as final. That's Gate 8 work, out of this session's scope.
- **No real radio data** — every `ChannelStats` sample here is
  synthetic. `wifi/` and `bluetooth/` (this session's skeletons) don't
  yet produce real `ChannelStats`, so this state machine has never seen
  an actual RTT/loss/RSSI reading from real hardware.
- **The residual-mode implications are moot** until §23's neural
  residual actually exists (`FeatureFlags.neuralResidualEnabled` is
  `false`) — `HIGH_FIDELITY_RESIDUAL` mode currently changes nothing
  observable in the pipeline; it only proves the *state machine* reaches
  that state correctly.
