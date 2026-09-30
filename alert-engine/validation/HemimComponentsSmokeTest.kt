// Standalone runtime smoke test for HEMIM's lower-level building blocks, mirroring
// alert-engine/src/test/kotlin/com/itantra/alert/HemimComponentsTest.kt one-for-one so that
// file's JUnit5 assertions all have a real, executed counterpart in this sandbox (JUnit
// itself is unreachable here — Maven Central is off the network allow-list, see
// docs/KNOWN_ISSUES.md). Companion to HemimSmokeTest.kt, which covers the full
// RuleBasedEmergencyAnalyzer integration path instead of these individual components.

import com.itantra.alert.ConfidenceFusion
import com.itantra.alert.CriticalEntity
import com.itantra.alert.CriticalEntityType
import com.itantra.alert.ContextAnalyzer
import com.itantra.alert.EmergencyCategory
import com.itantra.alert.EmergencyPolicy
import com.itantra.alert.EmergencyPriority
import com.itantra.alert.EmergencySignal
import com.itantra.alert.EmergencySignalSource
import com.itantra.alert.NegationDetector
import com.itantra.alert.TextNormalizer

private var checks = 0
private var failures = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (condition) println("PASS: $name") else { failures++; println("FAIL: $name") }
}

fun main() {
    println("=== HEMIM component-level runtime smoke test ===")

    // TextNormalizer
    run {
        val out = TextNormalizer.normalize("  There IS   a Fire!  3 people trapped.  ")
        check("normalizer lowercases/collapses whitespace, preserves punctuation+digits", out == "there is a fire! 3 people trapped.")
    }

    // NegationDetector
    run {
        val text = TextNormalizer.normalize("There is no fire here.")
        val f = NegationDetector.findNegation(text, text.indexOf("fire"))
        check("negation cue within window is detected", f.negated && f.cue == "no")
    }
    run {
        val text = TextNormalizer.normalize("There is a fire here.")
        val f = NegationDetector.findNegation(text, text.indexOf("fire"))
        check("no negation cue present is not falsely flagged", !f.negated)
    }
    run {
        val text = TextNormalizer.normalize("There is no rain. There is a fire.")
        val f = NegationDetector.findNegation(text, text.indexOf("fire"))
        check("negation cue does not cross a sentence boundary", !f.negated)
    }

    // ContextAnalyzer
    run {
        val text = TextNormalizer.normalize("Is there a fire?")
        val f = ContextAnalyzer.analyze(text, text.indexOf("fire"), text.indexOf("fire") + 4)
        check("question mark detected as question form", f.isQuestion)
    }
    run {
        val text = TextNormalizer.normalize("Someone reported a fire yesterday.")
        val f = ContextAnalyzer.analyze(text, text.indexOf("fire"), text.indexOf("fire") + 4)
        check("historical cue detected", f.isHistorical)
    }
    run {
        val text = TextNormalizer.normalize("The fire was extinguished.")
        val f = ContextAnalyzer.analyze(text, text.indexOf("fire"), text.indexOf("fire") + 4)
        check("resolved cue detected", f.isResolved)
    }

    // EmergencyPolicy
    run {
        check("base priority: TRAPPED_PERSON -> P0", EmergencyPolicy.basePriorityFor(EmergencyCategory.TRAPPED_PERSON) == EmergencyPriority.P0)
        check("base priority: FIRE -> P0", EmergencyPolicy.basePriorityFor(EmergencyCategory.FIRE) == EmergencyPriority.P0)
        check("base priority: EVACUATION -> P2", EmergencyPolicy.basePriorityFor(EmergencyCategory.EVACUATION) == EmergencyPriority.P2)
        check("base priority: GENERAL_ASSISTANCE -> P3", EmergencyPolicy.basePriorityFor(EmergencyCategory.GENERAL_ASSISTANCE) == EmergencyPriority.P3)
        check("base priority: NORMAL_MESSAGE -> P4", EmergencyPolicy.basePriorityFor(EmergencyCategory.NORMAL_MESSAGE) == EmergencyPriority.P4)
        check("base priority: UNKNOWN -> UNKNOWN", EmergencyPolicy.basePriorityFor(EmergencyCategory.UNKNOWN) == EmergencyPriority.UNKNOWN)
    }
    run {
        val severe = listOf(CriticalEntity(CriticalEntityType.SEVERITY, "severe"))
        check("severity escalates ACCIDENT P1 -> P0", EmergencyPolicy.priorityFor(EmergencyCategory.ACCIDENT, severe) == EmergencyPriority.P0)
        check("no severity entity leaves ACCIDENT at P1", EmergencyPolicy.priorityFor(EmergencyCategory.ACCIDENT, emptyList()) == EmergencyPriority.P1)
        check("severity escalation does not apply to non-escalatable FIRE (already P0)", EmergencyPolicy.priorityFor(EmergencyCategory.FIRE, severe) == EmergencyPriority.P0)
    }

    // ConfidenceFusion
    run {
        check("zero weight -> zero confidence", ConfidenceFusion.fuse(0.0, false, false) == 0.0)
        val active = ConfidenceFusion.fuse(3.0, false, false)
        val asQuestion = ConfidenceFusion.fuse(3.0, true, false)
        check("question penalty strictly reduces confidence", asQuestion < active)
        val huge = ConfidenceFusion.fuse(1_000_000.0, false, false)
        check("confidence always bounded to [0,1]", huge in 0.0..1.0)
    }

    // Data-class validation
    run {
        var threw = false
        try { CriticalEntity(CriticalEntityType.LOCATION, "bridge", startIndex = 10, endIndex = 5) } catch (e: IllegalArgumentException) { threw = true }
        check("CriticalEntity rejects endIndex < startIndex", threw)
    }
    run {
        var threw = false
        try { EmergencySignal(EmergencyCategory.FIRE, "fire", weight = -1.0, source = EmergencySignalSource.RULE, explanation = "x") } catch (e: IllegalArgumentException) { threw = true }
        check("EmergencySignal rejects a negative weight", threw)
    }

    println()
    println("=== $checks checks run, ${checks - failures} passed, $failures failed ===")
    if (failures > 0) kotlin.system.exitProcess(1)
}
