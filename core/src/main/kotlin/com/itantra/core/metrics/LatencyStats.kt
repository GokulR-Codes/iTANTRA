package com.itantra.core.metrics

/**
 * Rolling latency sample store + percentile calculator.
 *
 * §34: "Report P50/P95/P99, not just an average — an average hides exactly
 * the tail latency a judge will notice live." §32 requires the same for the
 * on-device benchmark dashboard. This is pure arithmetic, shared by the STT
 * pipeline (finalization latency), the TTS pipeline (first-audio latency,
 * see tts/TtsLatencyTracker.kt) and, later, Division D's transport metrics.
 */
class LatencyStats(private val maxSamples: Int = 2000) {
    private val samplesMs = ArrayDeque<Double>()

    fun record(sampleMs: Double) {
        samplesMs.addLast(sampleMs)
        if (samplesMs.size > maxSamples) samplesMs.removeFirst()
    }

    fun count(): Int = samplesMs.size

    fun percentile(p: Double): Double {
        require(p in 0.0..100.0) { "percentile must be in [0,100], got $p" }
        if (samplesMs.isEmpty()) return Double.NaN
        val sorted = samplesMs.sorted()
        val rank = (p / 100.0) * (sorted.size - 1)
        val lowerIdx = kotlin.math.floor(rank).toInt()
        val upperIdx = kotlin.math.ceil(rank).toInt()
        if (lowerIdx == upperIdx) return sorted[lowerIdx]
        val weight = rank - lowerIdx
        return sorted[lowerIdx] * (1 - weight) + sorted[upperIdx] * weight
    }

    fun p50() = percentile(50.0)
    fun p95() = percentile(95.0)
    fun p99() = percentile(99.0)
    fun mean(): Double = if (samplesMs.isEmpty()) Double.NaN else samplesMs.average()

    fun snapshot(): LatencySnapshot = LatencySnapshot(
        count = count(), meanMs = mean(), p50Ms = p50(), p95Ms = p95(), p99Ms = p99()
    )

    fun clear() = samplesMs.clear()
}

data class LatencySnapshot(
    val count: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double
)
