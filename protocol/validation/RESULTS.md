# Protocol Layer — Validation Results

**Status:** `[MEASURED]` — executed in this session's sandbox.
**Command:** `python3 protocol_reference.py`
**Environment:** sandbox container, no network, Python 3 (system), no
kotlinc/gradle/Android SDK available — see `KNOWN_ISSUES.md` for why the
actual Kotlin/JUnit tests in `protocol/src/test/` have not been compiled
or executed yet.
**Date:** 2026-09-05
**What was actually tested:** the CRC-16/CCITT-FALSE algorithm, the §24
24-byte-header + variable-payload + 2-byte-CRC-trailer wire format
(encode/decode round trip, corruption/truncation/bad-magic rejection),
and the replay-window duplicate/reorder logic — all mirrored line-for-line
from the Kotlin source into Python and run against concrete byte
sequences, not asserted from reading the code.

## Result

```
protocol_reference.py — 18/18 checks passed
```

Full output: see `protocol_reference_output.txt` in this directory
(captured verbatim from the run above).

## Key findings

1. **Header is exactly 24 bytes**, confirmed by direct measurement of the
   `struct.pack` output — this resolves the blueprint's own internal
   26-vs-24-byte inconsistency (§24) in code, not just in prose.
2. **Bug found and fixed during this session**: the first run of this
   script used a Unix-epoch-scale value for `timestampMs` and the round
   trip failed — the wire field is only 4 bytes (32-bit unsigned ms
   counter, ~49.7-day rollover), so an epoch value silently truncates.
   Root cause: the field was always documented as "sender-side monotonic
   ms," but nothing stopped a caller from passing epoch time. Fixed by
   (a) using a session-relative monotonic value in the test, (b) adding
   an explicit test that asserts the truncation behavior on purpose
   (`epoch-scale timestamps truncate to low 32 bits`), and (c) adding a
   `WARNING` block to the KDoc on `Packet.timestampMs` in
   `PacketFormat.kt` telling implementers to populate it from
   `SystemClock.elapsedRealtime() - sessionStartMs`, never
   `System.currentTimeMillis()`. Logged as `DECISIONS.md` entry #3.
3. **CRC-16/CCITT-FALSE implementation matches the standard check value**
   (0x29B1 for ASCII `"123456789"`), so it is not a home-grown variant
   masquerading as a standard algorithm.
4. **Replay window correctly accepts/rejects** strictly-increasing,
   exact-duplicate, too-old, and late-but-in-window sequence numbers —
   the four cases that actually matter for §36's loss/reorder/duplicate
   test matrix.
5. **Packet-loss simulation at 0/5/10/20% random loss** (seeded RNG,
   seed=1234, per §36's reproducibility requirement) confirms: of the
   packets that DO arrive uncorrupted, 100% are correctly decoded and
   accepted exactly once by the replay window at every loss level tested.
   This is a necessary-but-not-sufficient result — it proves the wire
   format and replay window don't themselves introduce loss or duplicate
   delivery; it does **not** yet prove end-to-end message delivery under
   loss, because retransmission/ACK-driven recovery (`RetransmissionQueue`,
   `SelectiveAckTracker`) was unit-tested in isolation (see
   `PacketFormatTest.kt`'s `ReliabilityLayerTest`) but not yet exercised
   in this same loss-injection harness. **Flagged as the next concrete
   task** in `TODO.md` — do not present the number above as "the system
   survives 20% loss" to a judge; it only shows the framing layer does.

## What this does NOT prove

- Nothing about real Wi-Fi/BLE radio behavior (`wifi/`, `bluetooth/` are
  unexecuted skeletons this session — see `KNOWN_ISSUES.md`).
- Nothing about the security layer (§30) — no encryption/auth is wired in
  yet; `ReplayWindow` here only defends against duplicate/replayed
  *sequence numbers*, not cryptographically authenticated replay attacks.
  §30/Gate 13 is separately scoped, not part of this session.
- Nothing about performance/latency on real hardware (§34) — this is
  correctness validation on a desktop Python interpreter, not a Class L
  device benchmark.
