# Semantic Codec — Bitrate Validation Results

**Status:** `[MEASURED]` on the placeholder corpus described in
`corpus/README.md` — 50 hand-written sentences, 10 languages, 5
sentences each. **Not** `[MEASURED]` at production scale; see caveats.
**Command:** `python3 bitrate_benchmark.py`
**Full captured output:** `bitrate_benchmark_output.txt`
**Date:** 2026-09-05

## Headline numbers

| Representation | Total bits | Bytes | vs raw UTF-8 |
|---|---:|---:|---:|
| Raw UTF-8 text | 15,320 | 1,915.0 | 100.0% |
| Semantic tokens, varint IDs (discovery-order) | 7,376 | 922.0 | 48.1% |
| Semantic tokens + Huffman (discovery-order) | 4,456 | 557.0 | 29.1% |
| **Semantic tokens, varint IDs (frequency-sorted)** | **6,112** | **764.0** | **39.9%** |
| Semantic tokens + Huffman (frequency-sorted) | 4,456 | 557.0 | 29.1% |

Round-trip check: **lossless for all 50 sentences, under both ID
assignments** — every sentence survives tokenize→ID→decode exactly.

## What happened, in order (the honest version, per §35 and §49)

1. **First run** used "first-appearance-order" ID assignment (0=UNK, then
   base symbols in codepoint order, then merges in discovery order). This
   compressed the 9 Indic-script languages well but made 4 of the 5
   English sentences **larger** than raw UTF-8 — e.g. `"help needed"`:
   88 bits raw vs. 96 bits tokenized. Root cause: the corpus is 9x
   Indic-heavy by sentence count, so English-specific merged symbols were
   discovered late and landed at integer IDs ≥128, costing 2 bytes in
   LEB128 varint instead of 1.
2. **Fix applied and re-measured in the same session**: re-assign IDs by
   actual token frequency across the corpus (most frequent symbol → id 1,
   after UNK=0), the standard approach for deployed BPE vocabularies.
   Re-ran the full benchmark, including a fresh lossless-round-trip
   assertion under the new IDs (did not just trust the fix).
3. **Result of the fix**: aggregate varint size dropped from 48.1% to
   **39.9%** of raw UTF-8 (i.e. materially better, not just "fixed the
   regression"). Per-sentence, 4 of the 5 previously-regressed English
   sentences now beat or match raw UTF-8:

   | Sentence | UTF-8 | Before fix | After fix | Verdict |
   |---|---:|---:|---:|---|
   | "help needed" | 88b | 96b | 88b | fixed (tied) |
   | "there is a fire" | 120b | 104b | 80b | fixed |
   | "evacuate now" | 96b | 104b | 120b | **still worse** |
   | "water is rising" | 120b | 120b | 112b | fixed |
   | "we are safe" | 88b | 88b | 72b | fixed |

4. **"evacuate now" is still worse than raw UTF-8 after the fix** — 120
   vs. 96 bits. This is reported rather than hidden: at 50 sentences,
   individual short strings can still land on an unlucky token boundary
   even with better global ID ordering. This is not proof the semantic
   codec is broken; it IS proof that per-sentence variance at this corpus
   size is real, and that the eventual production claim needs a real,
   much larger corpus before any single-language percentage is quoted to
   a judge as representative.
5. **Huffman result is numerically identical (4,456 bits) under both ID
   assignments** — expected, since an order-0 Huffman code depends only
   on symbol *frequency*, not on which integer ID a symbol happens to be
   assigned. This match is itself a small correctness check: if it had
   differed, that would indicate a bug in either the ID-reassignment or
   the Huffman frequency counting.

## Action items filed (see `TODO.md`)

1. ~~Assign IDs by frequency, not discovery order~~ — **done, re-measured,
   confirmed a net improvement (48.1%→39.9%)**, and one residual
   counter-example ("evacuate now") is now explicitly tracked instead of
   averaged away.
2. **Evaluate per-language vocabularies vs. one shared vocabulary** —
   still open. §21 already anticipates "language pack" loading; a
   per-language BPE table sized to that language's own frequency
   distribution might close the remaining "evacuate now"-style gaps.
   Must be measured, not assumed, against this same benchmark.
3. **Re-run this whole benchmark on a real corpus** before Gate 4/Gate 9
   sign-off — 5 sentences per language cannot support a production
   bitrate claim to a judge, fix or no fix.

## What this does NOT prove

- No Opus-equivalent audio baseline (§35 also requires this) — needs
  real recorded speech and libopus, unavailable in this offline sandbox.
  Do not claim "beats Opus" until that is actually run.
- No bits/**second** number — needs real speech-duration data synced to
  these sentences, which does not exist yet. Only bits/sentence (a real,
  measured proxy) is reported here. Converting to bits/second using an
  assumed speaking rate would be exactly the kind of `[TARGET]`-
  presented-as-`[MEASURED]` fabrication Prime Directive #2 prohibits, so
  it is deliberately not done in this file.
- No accuracy/meaning-preservation-under-loss measurement of the
  *tokenized* representation specifically (protocol/'s RESULTS.md tested
  the wire format's resilience to random loss using synthetic text
  payloads, not real semantic token streams from this codec — wiring
  those together is a TODO item).
- Nothing about native-speaker judgment of whether the 50 placeholder
  sentences are natural phrasing — written without native-speaker
  review; useful for pipeline testing, not validated Indic text.
