package com.itantra.tts

import com.itantra.core.metrics.LatencyStats

/**
 * §20/§34: "Track and report first-audio latency (time from 'text received'
 * to 'first playable audio sample') separately from total synthesis time —
 * the PS explicitly names this delay as a metric, so it needs its own
 * number, not just an RTF average."
 */
class TtsLatencyTracker {
    val firstAudioLatencyMs = LatencyStats()
    val totalSynthesisMs = LatencyStats()

    fun record(textReceivedAtMs: Long, firstAudioReadyAtMs: Long, synthesisCompleteAtMs: Long) {
        firstAudioLatencyMs.record((firstAudioReadyAtMs - textReceivedAtMs).toDouble())
        totalSynthesisMs.record((synthesisCompleteAtMs - textReceivedAtMs).toDouble())
    }
}
