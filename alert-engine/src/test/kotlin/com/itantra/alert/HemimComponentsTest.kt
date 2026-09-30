package com.itantra.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lower-level unit tests for the HEMIM building blocks, complementing the integration-style
 *  coverage in [RuleBasedEmergencyAnalyzerTest] (master prompt §27A). Same execution caveat
 *  as that file applies — see its class doc comment. */
class HemimComponentsTest {

    // --- TextNormalizer ---

    @Test
    fun `normalizer lowercases and collapses whitespace but preserves punctuation and digits`() {
        val out = TextNormalizer.normalize("  There IS   a Fire!  3 people trapped.  ")
        assertEquals("there is a fire! 3 people trapped.", out)
    }

    // --- NegationDetector ---

    @Test
    fun `negation cue within window before match is detected`() {
        val text = TextNormalizer.normalize("There is no fire here.")
        val matchStart = text.indexOf("fire")
        val finding = NegationDetector.findNegation(text, matchStart)
        assertTrue(finding.negated)
        assertEquals("no", finding.cue)
    }

    @Test
    fun `no negation cue present is not falsely flagged`() {
        val text = TextNormalizer.normalize("There is a fire here.")
        val matchStart = text.indexOf("fire")
        val finding = NegationDetector.findNegation(text, matchStart)
        assertFalse(finding.negated)
    }

    @Test
    fun `negation cue does not cross a sentence boundary`() {
        val text = TextNormalizer.normalize("There is no rain. There is a fire.")
        val matchStart = text.indexOf("fire")
        val finding = NegationDetector.findNegation(text, matchStart)
        assertFalse(finding.negated, "negation cue belongs to the previous sentence and must not leak across '.'")
    }

    // --- ContextAnalyzer ---

    @Test
    fun `question mark in the sentence is detected as question form`() {
        val text = TextNormalizer.normalize("Is there a fire?")
        val matchStart = text.indexOf("fire")
        val finding = ContextAnalyzer.analyze(text, matchStart, matchStart + 4)
        assertTrue(finding.isQuestion)
    }

    @Test
    fun `historical cue is detected`() {
        val text = TextNormalizer.normalize("Someone reported a fire yesterday.")
        val matchStart = text.indexOf("fire")
        val finding = ContextAnalyzer.analyze(text, matchStart, matchStart + 4)
        assertTrue(finding.isHistorical)
    }

    @Test
    fun `resolved cue is detected`() {
        val text = TextNormalizer.normalize("The fire was extinguished.")
        val matchStart = text.indexOf("fire")
        val finding = ContextAnalyzer.analyze(text, matchStart, matchStart + 4)
        assertTrue(finding.isResolved)
    }

    // --- EmergencyPolicy ---

    @Test
    fun `base priority table matches master-prompt policy candidates`() {
        assertEquals(EmergencyPriority.P0, EmergencyPolicy.basePriorityFor(EmergencyCategory.TRAPPED_PERSON))
        assertEquals(EmergencyPriority.P0, EmergencyPolicy.basePriorityFor(EmergencyCategory.FIRE))
        assertEquals(EmergencyPriority.P2, EmergencyPolicy.basePriorityFor(EmergencyCategory.EVACUATION))
        assertEquals(EmergencyPriority.P3, EmergencyPolicy.basePriorityFor(EmergencyCategory.GENERAL_ASSISTANCE))
        assertEquals(EmergencyPriority.P4, EmergencyPolicy.basePriorityFor(EmergencyCategory.NORMAL_MESSAGE))
        assertEquals(EmergencyPriority.UNKNOWN, EmergencyPolicy.basePriorityFor(EmergencyCategory.UNKNOWN))
    }

    @Test
    fun `severity entity escalates an escalatable category by exactly one level`() {
        val severe = listOf(CriticalEntity(CriticalEntityType.SEVERITY, "severe"))
        assertEquals(EmergencyPriority.P0, EmergencyPolicy.priorityFor(EmergencyCategory.ACCIDENT, severe))
        assertEquals(EmergencyPriority.P1, EmergencyPolicy.priorityFor(EmergencyCategory.ACCIDENT, emptyList()))
    }

    @Test
    fun `severity escalation never applies to a non-escalatable category`() {
        val severe = listOf(CriticalEntity(CriticalEntityType.SEVERITY, "severe"))
        assertEquals(EmergencyPriority.P0, EmergencyPolicy.priorityFor(EmergencyCategory.FIRE, severe))
    }

    // --- ConfidenceFusion ---

    @Test
    fun `zero weight yields zero confidence`() {
        assertEquals(0.0, ConfidenceFusion.fuse(0.0, allActiveAreQuestions = false, allActiveAreHistorical = false))
    }

    @Test
    fun `question penalty strictly reduces confidence relative to an equivalent active report`() {
        val active = ConfidenceFusion.fuse(3.0, allActiveAreQuestions = false, allActiveAreHistorical = false)
        val asQuestion = ConfidenceFusion.fuse(3.0, allActiveAreQuestions = true, allActiveAreHistorical = false)
        assertTrue(asQuestion < active)
    }

    @Test
    fun `confidence is always bounded to the closed unit interval`() {
        val c = ConfidenceFusion.fuse(1_000_000.0, allActiveAreQuestions = false, allActiveAreHistorical = false)
        assertTrue(c in 0.0..1.0)
    }

    // --- CriticalEntity data-class validation ---

    @Test
    fun `CriticalEntity rejects an end index before its start index`() {
        assertThrowsIllegalArgument {
            CriticalEntity(CriticalEntityType.LOCATION, "bridge", startIndex = 10, endIndex = 5)
        }
    }

    @Test
    fun `EmergencySignal rejects a negative weight`() {
        assertThrowsIllegalArgument {
            EmergencySignal(EmergencyCategory.FIRE, "fire", weight = -1.0, source = EmergencySignalSource.RULE, explanation = "x")
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "expected IllegalArgumentException")
    }
}
