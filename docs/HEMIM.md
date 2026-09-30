# HEMIM — Hybrid Emergency Message Intelligence Model

**Status: V1-V3 `[IMPLEMENTED]`, real-executed in this sandbox. V4-V6 `[BLOCKED]`/not started — see §"What's not here" below.**

HEMIM is iTANTRA's offline emergency-message analysis subsystem: given raw message text, it
produces an [`EmergencyAnalysis`](../alert-engine/src/main/kotlin/com/itantra/alert/EmergencyAnalysis.kt)
carrying a category, a priority, a confidence estimate, extracted critical entities, and a
human-readable explanation — without ever needing a network connection.

This document follows the same status-label discipline as the rest of `docs/`:
`[IMPLEMENTED]`, `[PARTIALLY IMPLEMENTED]`, `[SIMULATION]`, `[INTERFACE ONLY]`,
`[UNVERIFIED]`, `[BLOCKED]`, `[RECOMMENDED]`.

## Where it lives

All HEMIM source is in the existing `alert-engine` module
(`alert-engine/src/main/kotlin/com/itantra/alert/`), alongside the pre-existing
`AlertEngine`/`UnimplementedAlertEngine` (audio-focus + notification priority policy — a
different concern; HEMIM does not touch audio). This was a deliberate reuse decision, not
a new module, per the driving prompt's "do not create duplicate emergency systems" and
"reuse the existing alert-engine module if appropriate" instructions — no equivalent
emergency-classification code existed anywhere in the repo before this session (confirmed
by a full-repo grep for "HEMIM"/"emergency" before writing anything).

## Version roadmap and what's actually done

The driving prompt specifies six versions (V1-V6). **This session implemented V1, V2, and
V3 — the entirety of that prompt's own "Priority 0" scope** — and nothing beyond it:

| Version | Scope | Status |
|---|---|---|
| V1 | Rule-based emergency detector | `[IMPLEMENTED]` — `EmergencyRule.kt`, `EmergencyRuleRegistry`, `RuleBasedEmergencyAnalyzer.kt` |
| V2 | + Critical entity extraction | `[IMPLEMENTED]` — `CriticalEntityExtractor.kt` (`RegexCriticalEntityExtractor`) |
| V3 | + Negation/context handling | `[IMPLEMENTED]` — `NegationDetector.kt`, `ContextAnalyzer.kt` |
| V4 | + Lightweight ML classifier | `[BLOCKED]` / not started — no model has been selected, trained, or bundled. `EmergencySignalSource.ML_CLASSIFIER` exists as an enum value only; nothing emits it. Per the driving prompt's own instruction ("do not begin with a complex neural model if the rule-based baseline and evaluation infrastructure are not ready"), this was deliberately deferred rather than stubbed with a fake classifier. |
| V5 | Evidence fusion + full uncertainty handling | `[PARTIALLY IMPLEMENTED]` — `ConfidenceFusion.kt` and `EmergencyPolicy.kt` exist and are real, tested logic, but they are the V1-V3-only baseline described in the driving prompt's §15/§13, not the cross-model-type fusion layer V5 describes (there is nothing to fuse yet without V4). |
| V6 | Multilingual support + evaluation | `[BLOCKED]` / not started. `EmergencyRuleRegistry.rulesFor(languageCode)` and `RegexCriticalEntityExtractor` are English-only; a non-English `languageCode` returns `EmergencyCategory.UNKNOWN` with `requiresReview = true` rather than silently applying English rules or guessing. No evaluation/held-out test set exists for any language. |

## Data contracts

`EmergencyCategory`, `EmergencyPriority`, `EmergencySignal`, `CriticalEntity`,
`EmergencyAnalysis` — one file each, matching the driving prompt's §9 field lists exactly.
See each file's KDoc for the reasoning behind specific design choices (e.g. why
`EmergencyAnalysis.confidence` is documented as heuristic, not calibrated; why
`CriticalEntity.confidence` is nullable; why `UNKNOWN` is a first-class, never-discarded
value throughout).

## How a message is classified (V1-V3 algorithm)

1. **Normalize** (`TextNormalizer`) — lowercase + collapse whitespace only. Numbers,
   negation words, and punctuation (including `?`) are preserved because later stages
   depend on them.
2. **Match rules** (`EmergencyRuleRegistry`, English only) against the normalized text.
   Each match becomes an `EmergencySignal`.
3. **Analyze context** for every match (`ContextAnalyzer`, which wraps `NegationDetector`):
   negation (word-window heuristic, does not cross sentence boundaries), question form
   (contains `?` or starts with an interrogative word), historical/reported framing (curated
   cue-phrase list), resolved-event framing (curated cue-phrase list).
4. **Score.** Signals negated or resolved are recorded but excluded from scoring. Among the
   remaining ("active") signals, the category with the highest summed weight wins.
5. **Assign priority** (`EmergencyPolicy` + inline rules in `RuleBasedEmergencyAnalyzer`):
   - If every active signal for the winning category is a question → priority `UNKNOWN`.
   - Else if every active signal is historically framed → priority capped at `P2`.
   - Else → `EmergencyPolicy.priorityFor(category, entities)`, a configurable
     category→priority table with a narrow, documented entity-informed escalation rule
     (a `SEVERITY` entity with value critical/severe/serious/major/life-threatening raises
     `FLOOD`/`ACCIDENT`/`NATURAL_DISASTER`/`EVACUATION`/`GENERAL_ASSISTANCE` by one level).
6. **Extract entities** (`RegexCriticalEntityExtractor`) over the *original* text,
   independently of the category outcome, so entities are attached even when the message
   was ultimately classified as `NORMAL_MESSAGE`.
7. **Compute confidence** (`ConfidenceFusion`) from the winning category's total active
   signal weight, with multiplicative penalties for all-question or all-historical framing.

No step ever discards a message, and `EmergencyAnalysis.reasons` always explains the
outcome — including *why* a signal was excluded from scoring.

## Documented limitations (not hidden)

- **English only.** Every other language returns `UNKNOWN` + `requiresReview`, not a wrong
  guess — but there is no multilingual coverage yet at all.
- **Exact-phrase rule matching misses reordered negation.** "There is no fire." is caught
  (the rule "fire" matches, then negation is detected before it). "We need no help." is
  *not* caught the same way — the rule phrase "need help" simply never matches "need no
  help" because the words are out of order, so the message falls through to
  `NORMAL_MESSAGE` via the "nothing matched" path rather than the "matched, then
  suppressed" path. The end result is the same (safe, non-emergency), but a developer
  reading `reasons` should not assume every negated construction produces a
  `suppressedByContext` signal — see `alert-engine/validation/RESULTS.md` finding #3.
- **Negation/question/historical detection is a curated-keyword heuristic**, not a parser.
  It does not handle negation appearing after the matched phrase, double negation, or
  cue words outside the hand-picked lists in `NegationDetector.kt`/`ContextAnalyzer.kt`.
- **Location extraction is a shallow prepositional-phrase grab** ("near"/"at"/"in"/"on"/...
  + following words). It over- and under-extracts on real text; treat it as a hint for a
  human reviewer, not a geocoding result.
- **Confidence is not a calibrated probability.** `ConfidenceFusion`'s calibration constant
  and penalty multipliers are hand-picked, not fitted to any labeled dataset. There is no
  labeled dataset yet — building one is V4/V6 work.
- **Entities don't carry their own negation status.** If "help" is extracted as a
  `REQUESTED_ASSISTANCE` entity from a negated sentence, that entity's negation context is
  only recoverable from the owning `EmergencyAnalysis`'s signals/reasons, not from the
  entity object itself.
- **Not wired into anything yet.** No `EmergencyMessageEnvelope`, no `RetransmissionQueue`/
  `ReliabilityLayer` priority mapping, no Android UI, no ML classifier. These are the
  driving prompt's own "Priority 1" and later items — see `docs/PENDING_WORK_MASTER.md`.

## Verification

Real `kotlinc 2.0.20` compiled `:core` + every new `alert-engine` HEMIM file with zero
errors/warnings this session (`tests/build-logs/2026-09-24_hemim_v1_core_alert-engine_compile.txt`).
Two standalone runner programs then called the real compiled classes directly (not a
reimplementation) and executed 64/64 checks passing, covering every adversarial case the
driving prompt names by name — see `alert-engine/validation/RESULTS.md` for the full
breakdown, methodology, and what this evidence does and does not prove.

Matching JUnit5 test sources exist at `alert-engine/src/test/kotlin/com/itantra/alert/`
(`RuleBasedEmergencyAnalyzerTest.kt`, `HemimComponentsTest.kt`) with the identical
assertions, so `./gradlew :alert-engine:testDebugUnitTest` reproduces this coverage once
Android SDK/Maven access exists (this sandbox has neither — see `docs/KNOWN_ISSUES.md`).
That Gradle run has **not** happened; the JUnit files are unexecuted source, not additional
verified evidence beyond the two runner programs above.

## Next steps (see `docs/PENDING_WORK_MASTER.md` for the authoritative backlog)

1. `EmergencyMessageEnvelope` wrapping `SemanticPacket` + `EmergencyAnalysis` (driving
   prompt §16).
2. Map `EmergencyPriority` onto the existing `ReliabilityLayer`/queue priority handling
   (§17) — inspect `ReliabilityLayer.kt` first; no `RetransmissionQueue` class exists under
   that name yet, so this needs its own small audit before implementation.
3. A minimal Android UI surface for `EmergencyAnalysis` (§20) — text input, category,
   priority, confidence/uncertainty indicator, entities, send action, delivery status.
4. Only after 1-3: HEMIM V4 (lightweight ML classifier, offline-capable, with a real
   held-out evaluation set) and V6 (additional languages).
