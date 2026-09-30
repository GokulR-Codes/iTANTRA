package com.itantra.alert

/**
 * Single construction point for [EmergencyAnalyzer] instances (master prompt §6), so
 * callers (Android UI, session pipeline, tests) never hand-wire [RuleBasedEmergencyAnalyzer]
 * + [RegexCriticalEntityExtractor] themselves. When HEMIM V4's ML classifier and V5's fusion
 * layer exist, this is the one place that changes to return the upgraded analyzer.
 */
object EmergencyAnalyzerFactory {
    /** The current default: HEMIM V1-V3, rule-based + entity extraction + context handling. */
    fun createDefault(): EmergencyAnalyzer = RuleBasedEmergencyAnalyzer()
}
