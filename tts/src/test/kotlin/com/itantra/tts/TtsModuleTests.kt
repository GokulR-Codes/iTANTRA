package com.itantra.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextFrontEndTest {

    @Test
    fun `collapses whitespace and trims`() {
        assertEquals("help now", TextFrontEnd.normalize("  help    now  "))
    }

    @Test
    fun `NFC-normalizes decomposed Unicode`() {
        val decomposed = "e\u0301" // 'e' + combining acute accent
        val normalized = TextFrontEnd.normalize(decomposed)
        assertEquals(1, normalized.codePointCount(0, normalized.length), "NFC should compose to a single code point")
    }
}

class TtsLatencyTrackerTest {

    @Test
    fun `records first-audio latency separately from total synthesis time`() {
        val tracker = TtsLatencyTracker()
        tracker.record(textReceivedAtMs = 1000, firstAudioReadyAtMs = 1120, synthesisCompleteAtMs = 1300)
        assertEquals(120.0, tracker.firstAudioLatencyMs.mean(), 0.001)
        assertEquals(300.0, tracker.totalSynthesisMs.mean(), 0.001)
        assertTrue(tracker.firstAudioLatencyMs.mean() < tracker.totalSynthesisMs.mean())
    }
}
