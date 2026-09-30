package com.itantra.core.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class LatencyStatsTest {

    @Test
    fun `percentiles and mean over 1 to 100`() {
        val stats = LatencyStats()
        (1..100).forEach { stats.record(it.toDouble()) }

        assertEquals(100, stats.count())
        assertTrue(abs(stats.p50() - 50.5) < 0.01)
        assertTrue(abs(stats.p95() - 95.05) < 0.01)
        assertTrue(abs(stats.p99() - 99.01) < 0.01)
        assertTrue(abs(stats.mean() - 50.5) < 0.01)
    }

    @Test
    fun `percentile on empty store is NaN, not a crash`() {
        val empty = LatencyStats()
        assertTrue(empty.p50().isNaN())
    }

    @Test
    fun `maxSamples bound evicts oldest samples`() {
        val stats = LatencyStats(maxSamples = 5)
        (1..10).forEach { stats.record(it.toDouble()) }
        assertEquals(5, stats.count())
        // Only 6..10 should remain, so the mean should reflect that window, not 1..10's.
        assertEquals(8.0, stats.mean(), 0.001)
    }
}
