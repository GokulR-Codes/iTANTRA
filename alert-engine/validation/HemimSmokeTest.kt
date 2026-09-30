// Standalone runtime smoke test for HEMIM V1-V3 (RuleBasedEmergencyAnalyzer +
// CriticalEntityExtractor + NegationDetector/ContextAnalyzer + EmergencyPolicy).
//
// UNLIKE wifi/validation/GoodputSmokeTest.kt, this file exercises the REAL compiled
// classes directly (com.itantra.alert.*), not a copied-out reimplementation — alert-engine's
// new HEMIM files have no Android or kotlinx-coroutines dependency, so they compile and run
// cleanly against the real Kotlin 2.0.20 compiler in this sandbox (see
// tests/build-logs/2026-09-24_hemim_v1_compile_and_smoketest.txt for the compile command).
// This IS evidence the real production classes behave as intended, not just that isolated
// arithmetic works.
//
// Covers the exact adversarial cases master prompt §11/§27 calls out by name.

import com.itantra.alert.EmergencyAnalyzerFactory
import com.itantra.alert.EmergencyCategory
import com.itantra.alert.EmergencyPriority

private var checks = 0
private var failures = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (condition) {
        println("PASS: $name")
    } else {
        failures++
        println("FAIL: $name")
    }
}

fun main() {
    println("=== HEMIM V1-V3 runtime smoke test ===")
    val analyzer = EmergencyAnalyzerFactory.createDefault()

    // --- Positive, active reports ---
    run {
        val a = analyzer.analyze("There is a fire near the school!", "en")
        check("'There is a fire near the school!' -> FIRE", a.category == EmergencyCategory.FIRE)
        check("'There is a fire...' -> priority P0", a.priority == EmergencyPriority.P0)
        check("'There is a fire...' -> isPotentialEmergency", a.isPotentialEmergency)
        check("'There is a fire...' -> confidence > 0", a.confidence > 0.0)
    }

    run {
        val a = analyzer.analyze("Three people are trapped near the bridge.", "en")
        check("'Three people are trapped near the bridge.' -> TRAPPED_PERSON", a.category == EmergencyCategory.TRAPPED_PERSON)
        check("trapped-near-bridge -> priority P0", a.priority == EmergencyPriority.P0)
        check("trapped-near-bridge -> PERSON_COUNT entity '3 people'", a.entities.any { it.type.name == "PERSON_COUNT" && it.value == "3 people" })
        check("trapped-near-bridge -> LOCATION entity contains 'bridge'", a.entities.any { it.type.name == "LOCATION" && it.value.contains("bridge") })
        check("trapped-near-bridge -> EVENT entity 'trapped'", a.entities.any { it.type.name == "EVENT" && it.value == "trapped" })
    }

    run {
        val a = analyzer.analyze("One person is unconscious and needs an ambulance.", "en")
        check("unconscious+ambulance -> MEDICAL", a.category == EmergencyCategory.MEDICAL)
        check("unconscious+ambulance -> priority P0", a.priority == EmergencyPriority.P0)
        check("unconscious+ambulance -> INJURY entity", a.entities.any { it.type.name == "INJURY" && it.value == "unconscious" })
        check("unconscious+ambulance -> MEDICAL_NEED entity", a.entities.any { it.type.name == "MEDICAL_NEED" && it.value == "ambulance" })
    }

    // --- Negation (§11, §27) ---
    run {
        val a = analyzer.analyze("There is no fire.", "en")
        check("'There is no fire.' -> NORMAL_MESSAGE (negated)", a.category == EmergencyCategory.NORMAL_MESSAGE)
        check("'There is no fire.' -> requiresReview true", a.requiresReview)
        check("'There is no fire.' -> signal recorded, not discarded", a.detectedSignals.isNotEmpty())
        check("'There is no fire.' -> the recorded signal is marked suppressed", a.detectedSignals.any { it.suppressedByContext })
    }

    run {
        val a = analyzer.analyze("We need no help.", "en")
        check("'We need no help.' -> NORMAL_MESSAGE (multi-word negation defeats exact-phrase rule)", a.category == EmergencyCategory.NORMAL_MESSAGE)
    }

    run {
        val a = analyzer.analyze("Do not send help.", "en")
        check("'Do not send help.' -> NORMAL_MESSAGE (negated assistance request)", a.category == EmergencyCategory.NORMAL_MESSAGE)
        check("'Do not send help.' -> requiresReview true", a.requiresReview)
    }

    // --- Resolved event (§11) ---
    run {
        val a = analyzer.analyze("The fire was extinguished.", "en")
        check("'The fire was extinguished.' -> NORMAL_MESSAGE (resolved)", a.category == EmergencyCategory.NORMAL_MESSAGE)
        check("'The fire was extinguished.' -> requiresReview true", a.requiresReview)
    }

    // --- Question form (§11, §27) ---
    run {
        val a = analyzer.analyze("Is there a fire?", "en")
        check("'Is there a fire?' -> priority UNKNOWN (question, not a report)", a.priority == EmergencyPriority.UNKNOWN)
        check("'Is there a fire?' -> requiresReview true", a.requiresReview)
        check("'Is there a fire?' -> category still FIRE (not silently discarded)", a.category == EmergencyCategory.FIRE)
    }

    run {
        val a = analyzer.analyze("Is anyone trapped?", "en")
        check("'Is anyone trapped?' -> priority UNKNOWN", a.priority == EmergencyPriority.UNKNOWN)
        check("'Is anyone trapped?' -> requiresReview true", a.requiresReview)
    }

    // --- Historical / reported framing (§11, §27) ---
    run {
        val a = analyzer.analyze("Someone reported a fire yesterday.", "en")
        check("'Someone reported a fire yesterday.' -> priority capped at P2", a.priority == EmergencyPriority.P2)
        check("'Someone reported a fire yesterday.' -> requiresReview true", a.requiresReview)
        check("'Someone reported a fire yesterday.' -> category still FIRE", a.category == EmergencyCategory.FIRE)
    }

    // --- Determinism (§13) ---
    run {
        val a1 = analyzer.analyze("There is a fire near the school!", "en")
        val a2 = analyzer.analyze("There is a fire near the school!", "en")
        check("identical input -> identical category/priority/confidence (deterministic)",
            a1.category == a2.category && a1.priority == a2.priority && a1.confidence == a2.confidence)
    }

    // --- Edge cases (§27 adversarial list) ---
    run {
        val a = analyzer.analyze("", "en")
        check("empty input -> UNKNOWN, not a crash", a.category == EmergencyCategory.UNKNOWN)
        check("empty input -> requiresReview true", a.requiresReview)
    }

    run {
        val a = analyzer.analyze("   ", "en")
        check("blank input -> UNKNOWN, not a crash", a.category == EmergencyCategory.UNKNOWN)
    }

    run {
        val a = analyzer.analyze("fire fire fire trapped trapped accident accident accident accident", "xx")
        check("unsupported language 'xx' -> UNKNOWN, not a wrong-language guess", a.category == EmergencyCategory.UNKNOWN)
        check("unsupported language 'xx' -> requiresReview true", a.requiresReview)
        check("unsupported language 'xx' -> reason names the language gap", a.reasons.any { it.contains("no HEMIM rule set") })
    }

    run {
        val longText = "There is a fire near the market. ".repeat(500)
        val a = analyzer.analyze(longText, "en")
        check("very long input (500 repeats) does not crash and still classifies FIRE", a.category == EmergencyCategory.FIRE)
    }

    run {
        val a1 = analyzer.analyze("Please send help, we are stuck.", "en")
        val a2 = analyzer.analyze("Please send help, we are stuck.", "en")
        check("duplicate message handling: both calls succeed independently", a1.category == a2.category)
    }

    run {
        val a = analyzer.analyze("Building collapsed after the earthquake, severe damage reported.", "en")
        check("severe entity escalates NATURAL_DISASTER from P1 base to P0", a.priority == EmergencyPriority.P0)
    }

    run {
        val a = analyzer.analyze("Thanks for the update, see you tomorrow.", "en")
        check("plain non-emergency text -> NORMAL_MESSAGE", a.category == EmergencyCategory.NORMAL_MESSAGE)
        check("plain non-emergency text -> priority P4", a.priority == EmergencyPriority.P4)
        check("plain non-emergency text -> requiresReview false", !a.requiresReview)
    }

    println()
    println("=== $checks checks run, ${checks - failures} passed, $failures failed ===")
    if (failures > 0) kotlin.system.exitProcess(1)
}
