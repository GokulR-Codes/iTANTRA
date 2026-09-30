package com.itantra.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for HEMIM V1-V3 (master prompt §27A/§27B).
 *
 * These same assertions were already executed for real, against the real compiled classes,
 * with a standalone `kotlinc`-compiled runner in this sandbox (Maven Central/AGP are not
 * reachable here — see docs/KNOWN_ISSUES.md) — see
 * `alert-engine/validation/HemimSmokeTest.kt` and
 * `tests/build-logs/2026-09-24_hemim_v1_compile_and_smoketest.txt` for that run's actual
 * PASS/FAIL output. This file is the same coverage in the project's normal JUnit5 form, to
 * run via `./gradlew :alert-engine:testDebugUnitTest` once Android SDK + Maven access is
 * available (§28) — it has not itself been executed by Gradle in this sandbox, and that gap
 * is stated here rather than implied away.
 */
class RuleBasedEmergencyAnalyzerTest {

    private val analyzer: EmergencyAnalyzer = RuleBasedEmergencyAnalyzer()

    @Test
    fun `direct fire report classifies as FIRE P0`() {
        val a = analyzer.analyze("There is a fire near the school!", "en")
        assertEquals(EmergencyCategory.FIRE, a.category)
        assertEquals(EmergencyPriority.P0, a.priority)
        assertTrue(a.isPotentialEmergency)
        assertTrue(a.confidence > 0.0)
    }

    @Test
    fun `trapped person report extracts person count, location, and event entities`() {
        val a = analyzer.analyze("Three people are trapped near the bridge.", "en")
        assertEquals(EmergencyCategory.TRAPPED_PERSON, a.category)
        assertEquals(EmergencyPriority.P0, a.priority)
        assertTrue(a.entities.any { it.type == CriticalEntityType.PERSON_COUNT && it.value == "3 people" })
        assertTrue(a.entities.any { it.type == CriticalEntityType.LOCATION && it.value.contains("bridge") })
        assertTrue(a.entities.any { it.type == CriticalEntityType.EVENT && it.value == "trapped" })
    }

    @Test
    fun `medical report extracts injury and medical-need entities`() {
        val a = analyzer.analyze("One person is unconscious and needs an ambulance.", "en")
        assertEquals(EmergencyCategory.MEDICAL, a.category)
        assertEquals(EmergencyPriority.P0, a.priority)
        assertTrue(a.entities.any { it.type == CriticalEntityType.INJURY && it.value == "unconscious" })
        assertTrue(a.entities.any { it.type == CriticalEntityType.MEDICAL_NEED && it.value == "ambulance" })
    }

    @Test
    fun `negated emergency phrase is preserved but not classified as active emergency`() {
        val a = analyzer.analyze("There is no fire.", "en")
        assertEquals(EmergencyCategory.NORMAL_MESSAGE, a.category)
        assertTrue(a.requiresReview)
        assertTrue(a.detectedSignals.isNotEmpty(), "negated signal must still be recorded, not discarded")
        assertTrue(a.detectedSignals.any { it.suppressedByContext })
    }

    @Test
    fun `multi-word negation not caught by exact-phrase matching is a documented gap, not a crash`() {
        val a = analyzer.analyze("We need no help.", "en")
        assertEquals(EmergencyCategory.NORMAL_MESSAGE, a.category)
    }

    @Test
    fun `explicit refusal of assistance is not classified as an assistance request`() {
        val a = analyzer.analyze("Do not send help.", "en")
        assertEquals(EmergencyCategory.NORMAL_MESSAGE, a.category)
        assertTrue(a.requiresReview)
    }

    @Test
    fun `resolved event is not classified as an active emergency`() {
        val a = analyzer.analyze("The fire was extinguished.", "en")
        assertEquals(EmergencyCategory.NORMAL_MESSAGE, a.category)
        assertTrue(a.requiresReview)
    }

    @Test
    fun `question form yields UNKNOWN priority without discarding the category`() {
        val a = analyzer.analyze("Is there a fire?", "en")
        assertEquals(EmergencyPriority.UNKNOWN, a.priority)
        assertTrue(a.requiresReview)
        assertEquals(EmergencyCategory.FIRE, a.category)
    }

    @Test
    fun `historical framing caps priority at P2`() {
        val a = analyzer.analyze("Someone reported a fire yesterday.", "en")
        assertEquals(EmergencyPriority.P2, a.priority)
        assertTrue(a.requiresReview)
        assertEquals(EmergencyCategory.FIRE, a.category)
    }

    @Test
    fun `identical input produces identical classification (deterministic)`() {
        val a1 = analyzer.analyze("There is a fire near the school!", "en")
        val a2 = analyzer.analyze("There is a fire near the school!", "en")
        assertEquals(a1.category, a2.category)
        assertEquals(a1.priority, a2.priority)
        assertEquals(a1.confidence, a2.confidence)
    }

    @Test
    fun `empty input does not throw and is flagged for review`() {
        val a = analyzer.analyze("", "en")
        assertEquals(EmergencyCategory.UNKNOWN, a.category)
        assertTrue(a.requiresReview)
    }

    @Test
    fun `blank input does not throw and is flagged for review`() {
        val a = analyzer.analyze("   ", "en")
        assertEquals(EmergencyCategory.UNKNOWN, a.category)
    }

    @Test
    fun `unsupported language returns UNKNOWN rather than a wrong-language guess`() {
        val a = analyzer.analyze("fire fire trapped accident", "xx")
        assertEquals(EmergencyCategory.UNKNOWN, a.category)
        assertTrue(a.requiresReview)
        assertTrue(a.reasons.any { it.contains("no HEMIM rule set") })
    }

    @Test
    fun `very long input does not throw and still classifies correctly`() {
        val longText = "There is a fire near the market. ".repeat(500)
        val a = analyzer.analyze(longText, "en")
        assertEquals(EmergencyCategory.FIRE, a.category)
    }

    @Test
    fun `severe entity escalates a P1-baseline category to P0`() {
        val a = analyzer.analyze("Building collapsed after the earthquake, severe damage reported.", "en")
        assertEquals(EmergencyPriority.P0, a.priority)
    }

    @Test
    fun `plain non-emergency text is NORMAL_MESSAGE P4 and not flagged for review`() {
        val a = analyzer.analyze("Thanks for the update, see you tomorrow.", "en")
        assertEquals(EmergencyCategory.NORMAL_MESSAGE, a.category)
        assertEquals(EmergencyPriority.P4, a.priority)
        assertFalse(a.requiresReview)
    }

    @Test
    fun `every EmergencyAnalysis carries a non-empty reasons list`() {
        val texts = listOf("There is a fire!", "no fire here", "", "random unrelated text")
        for (t in texts) {
            assertTrue(analyzer.analyze(t, "en").reasons.isNotEmpty(), "reasons must be non-empty for input: '$t'")
        }
    }
}
