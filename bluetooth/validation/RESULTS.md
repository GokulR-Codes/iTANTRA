# BLE Transport Logic — Validation Results

**Status:** `[MEASURED]` for the pure-logic pieces only (fragmentation
math, windowed ACK). **NOT measured:** anything requiring real BLE
hardware or the Android SDK — see `KNOWN_ISSUES.md`.
**Command:** `python3 ble_logic_reference.py`
**Output:** `ble_logic_output.txt`
**Date:** 2026-09-05

## Result

```
ble_logic_reference.py — 14/14 checks passed
```

## Key finding: the current header does not fit in the BLE spec's minimum MTU

The first version of this script asserted MTU=23 (the BLE 4.0 spec
floor) would yield *some* usable fragment payload. It didn't — it threw,
because:

```
usable_after_ATT_header = 23 - 3  = 20
usable_after_our_header = 20 - 24 = -4   (our header is 24 bytes, larger than what's left)
```

**The §24 wire format needs a negotiated ATT_MTU of at least 30 to carry
even one byte of application payload.** BLE legally allows a peer to
never negotiate above 23. This is a real architectural risk, not a test
bug — logged as `DECISIONS.md` entry #5 and `KNOWN_ISSUES.md`, and fixed
at the source level (not just documented): `BleFragmenter.fragment()` and
`BleGattTransport.onMtuNegotiated()` in the Kotlin source now throw
`BleMtuTooSmallException` explicitly when this happens, rather than
silently producing a corrupt or empty fragment. §30's fail-closed
parsing philosophy applied one layer up, to fail-closed *transport
capability*, not just fail-closed *parsing*.

**This is not resolved, only caught.** Two candidate resolutions for a
future session (tracked in `TODO.md`, both requiring real BLE hardware
to validate):
1. Treat a sub-30-byte negotiated MTU as "BLE unusable for this peer"
   and force the Tier fallback (§42) to Wi-Fi or a retry-negotiation
   path — simplest, but means very old/restrictive BLE stacks get no
   BLE path at all.
2. Design a compact BLE-specific header variant (fewer/narrower fields)
   for exactly this degraded case — more engineering risk, deferred
   until it's shown to matter on real target hardware (most modern
   Android BLE stacks negotiate 185–247 without issue; how common the
   sub-30 case actually is on Class L devices is itself unmeasured).

## Other confirmed behaviors

- Fragmentation at both a tight-but-functional MTU (32) and a realistic
  BLE 4.2+ MTU (185) reassembles the test message byte-exact.
- Smaller MTU produces more fragments than larger MTU, monotonically, as
  expected.
- Fragment count for a realistic multi-hundred-byte message stays well
  within the §24 1-byte fragment-count field (≤255).
- `WindowedAckTracker` correctly slides forward on in-order ACKs, holds
  at a gap on out-of-order ACKs, catches up once the gap is filled, and
  ignores ACKs for sequence numbers outside the current window rather
  than corrupting window state.

## What this does NOT prove

- Nothing about real `BluetoothGatt`/`BluetoothGattServer` callback
  behavior, connection state transitions, or actual MTU negotiation
  round trips — no Android SDK or BLE hardware in this sandbox.
  `BleGattTransport`'s reconnect state machine is explicitly left as a
  TODO in the Kotlin source rather than guessed at, per §9's "don't
  select/design against unverified assumptions" methodology.
- Nothing about how often real Class L devices actually hit the
  sub-30-byte MTU case in practice — that requires real-device testing
  (Gate 7), not simulation.
