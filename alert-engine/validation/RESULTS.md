# HEMIM V1-V3 — Validation Results

**Status:** `[MEASURED]` — compiled and executed in this session's sandbox against the
real, real production classes (not a copied-out reimplementation).
**Commands:**
```
kotlinc @core_and_alert_engine_file_list.txt -d alert_main.jar
kotlinc -cp alert_main.jar alert-engine/validation/HemimSmokeTest.kt -d hemim_smoketest.jar
java -cp alert_main.jar:hemim_smoketest.jar:kotlin-stdlib.jar HemimSmokeTestKt

kotlinc -cp alert_main.jar alert-engine/validation/HemimComponentsSmokeTest.kt -d hemim_components_smoketest.jar
java -cp alert_main.jar:hemim_components_smoketest.jar:kotlin-stdlib.jar HemimComponentsSmokeTestKt
```
**Environment:** sandbox container, kotlinc-jvm 2.0.20 downloaded directly from
`github.com/JetBrains/kotlin` releases (Maven Central is off this session's network
allow-list — same constraint `docs/KNOWN_ISSUES.md` records for the rest of the project;
JDK 21). No Android SDK, no Gradle network access, so this is a plain-JVM compile of
`:core` + `:alert-engine`'s new HEMIM files only — `AlertEngine.kt` itself has no Android
import either, so the whole module compiled, not just the new files.
**Date:** 2026-09-24.
**What was actually tested:** the real `RuleBasedEmergencyAnalyzer`, `EmergencyPolicy`,
`ConfidenceFusion`, `NegationDetector`, `ContextAnalyzer`, `TextNormalizer`, and
`RegexCriticalEntityExtractor` classes, called directly (not reimplemented) — including
every adversarial case the master prompt names by name in §11/§27B: negation ("There is no
fire."), resolved events ("The fire was extinguished."), questions ("Is there a fire?",
"Is anyone trapped?"), historical/reported framing ("Someone reported a fire yesterday."),
explicit refusal ("Do not send help."), multi-word negation ("We need no help."), empty and
blank input, an unsupported language code, a 500x-repeated long input, determinism on
repeated identical input, and entity-informed priority escalation.

## Result

```
HemimSmokeTest.kt           — 43/43 checks passed
HemimComponentsSmokeTest.kt — 21/21 checks passed
Total                       — 64/64 checks passed
```

Full output: `hemim_smoketest_output.txt` and `hemim_components_smoketest_output.txt` in
this directory (captured verbatim from the runs above). Compile log:
`tests/build-logs/2026-09-24_hemim_v1_core_alert-engine_compile.txt` (empty output from
`kotlinc` = zero errors/warnings).

## Key findings

1. **This is stronger evidence than the wifi/protocol validation scripts elsewhere in this
   repo**, which reimplement logic in Python or a coroutine-free Kotlin copy because the
   real classes can't be compiled in this sandbox (kotlinx-coroutines needs Maven Central).
   HEMIM's V1-V3 source has zero Android and zero third-party dependency, so the smoke
   tests here call `com.itantra.alert.RuleBasedEmergencyAnalyzer` etc. directly — a pass
   here is evidence about the actual production class, not a parallel model of it.
2. **The JUnit5 test sources** (`alert-engine/src/test/kotlin/com/itantra/alert/
   RuleBasedEmergencyAnalyzerTest.kt`, `HemimComponentsTest.kt`) encode the identical
   assertions as the two smoke tests above, one-for-one, so `./gradlew
   :alert-engine:testDebugUnitTest` will re-run this exact coverage once Android SDK/Maven
   access exists — but that Gradle run has **not** happened in this sandbox. Do not read
   the JUnit files as independently-verified; they are the same evidence in the project's
   normal test form, not additional evidence.
3. **"We need no help."** is a deliberate negative result, not a bug: because the V1 rule
   matcher does exact-phrase matching, the GENERAL_ASSISTANCE rule "need help" does not
   match "need no help" (the words are out of order), so no rule fires at all and the
   message falls through to NORMAL_MESSAGE by default (no signals matched) rather than via
   negation suppression (a signal matched and was then suppressed). The end classification
   is the same and still safe, but the *reason* differs from "There is no fire." — this gap
   is called out explicitly in `docs/HEMIM.md`'s limitations section, not hidden.
4. **Entity-informed escalation is narrow by design**: only `FLOOD`, `ACCIDENT`,
   `NATURAL_DISASTER`, `EVACUATION`, and `GENERAL_ASSISTANCE` can be escalated one priority
   level by a `SEVERITY` entity (`critical`/`severe`/`serious`/`major`/`life-threatening`).
   `MEDICAL`, `FIRE`, `TRAPPED_PERSON`, `SECURITY_THREAT` are already at the P0 baseline and
   are unaffected — confirmed by the "severity escalation does not apply to non-escalatable
   FIRE" check.
5. **Not tested here:** anything requiring Android (UI, lifecycle), the priority-queue
   integration (`ReliabilityLayer`), the emergency envelope, multilingual rules (V6), or an
   ML classifier (V4) — none of that exists yet. See `docs/HEMIM.md` and
   `docs/PENDING_WORK_MASTER.md` for what's next.
