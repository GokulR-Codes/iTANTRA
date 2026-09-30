# datasets/

Provenance and license manifests for every dataset the project touches
(§40) — **not the datasets themselves**. Large audio/text corpora do not
belong in this git repository; this directory tracks *where each dataset
came from, under what license, and what subset/version was used*, so a
license auditor or a future contributor never has to guess.

## Layout
- `manifests/` — one file per dataset: source URL, license, date
  downloaded/accessed, exact subset/split used, and a checksum of what was
  actually used (not just "the dataset" — datasets change upstream)
- `schemas/` — JSON Schema (or equivalent) for the manifest format itself,
  so `manifests/*.json` can be validated mechanically in CI rather than by
  eyeballing

**Status: empty.** No dataset has been ingested yet — Gate 1 ships
interfaces, not model training. The first manifest should be added the
same day the first dataset is actually downloaded, not after the fact.
