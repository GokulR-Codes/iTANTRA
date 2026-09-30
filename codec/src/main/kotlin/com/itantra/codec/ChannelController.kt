package com.itantra.codec

import com.itantra.core.ChannelMode
import com.itantra.core.ChannelStats
import com.itantra.core.ThermalState

/**
 * INTEGRATION NOTE: the Part IV archive this was ported from declared its own local
 * `enum class ChannelMode { SEMANTIC_SAFE, SEMANTIC_PLUS_RESIDUAL, HIGH_FIDELITY_RESIDUAL }`
 * in this file — identical in shape to `core.Contracts.ChannelMode`, which the Gate1 git
 * archive had already established as the shared vocabulary. That local duplicate is deleted
 * here; this file imports the core enum instead (see docs/FILE_CONFLICT_MATRIX.md).
 * Also adapted: the original checked `ThermalState.THROTTLING`/`CRITICAL` (its own 4-value
 * enum); the merged `core.ThermalState` has 6 values, so the equivalent hard-gate condition
 * here is `SEVERE`/`CRITICAL` (see Contracts.kt's ThermalState merge note).
 */

/** Exponentially-weighted moving average smoother for one scalar channel-quality signal. */
class Ewma(private val alpha: Double = 0.3) {
    private var value: Double? = null

    fun update(sample: Double): Double {
        val prev = value
        val next = if (prev == null) sample else alpha * sample + (1 - alpha) * prev
        value = next
        return next
    }

    fun current(): Double? = value
}

/**
 * §27: "Smooth with an EWMA and apply hysteresis so the controller does
 * not thrash between modes on noisy single-sample readings."
 *
 * All thresholds below are `[TARGET]` starting envelopes, explicitly not
 * `[MEASURED]` — §27 requires them to be "validated empirically against
 * the packet-loss test matrix in §36" (0/5/10/20% random + burst +
 * reorder + duplication + corruption) before shipping. That test matrix
 * is P4/benchmark scope, not this merge's — the *mechanism* is real and
 * wired to the canonical contracts; the constants are starting points for
 * later tuning, not final numbers.
 */
class AdaptiveChannelController(
    private val downgradeLossPct: Double = 8.0,
    private val upgradeLossPct: Double = 3.0, // must be < downgradeLossPct: hysteresis gap
    private val downgradeRttMs: Double = 400.0,
    private val upgradeRttMs: Double = 150.0,
    private val minGoodputForResidualBps: Double = 8_000.0,
    private val ewmaAlpha: Double = 0.3
) {
    init {
        require(upgradeLossPct < downgradeLossPct) {
            "upgradeLossPct must be strictly less than downgradeLossPct to provide hysteresis " +
                "(got upgrade=$upgradeLossPct downgrade=$downgradeLossPct)"
        }
        require(upgradeRttMs < downgradeRttMs) {
            "upgradeRttMs must be strictly less than downgradeRttMs to provide hysteresis"
        }
    }

    private val lossEwma = Ewma(ewmaAlpha)
    private val rttEwma = Ewma(ewmaAlpha)
    private val goodputEwma = Ewma(ewmaAlpha)

    private var currentMode: ChannelMode = ChannelMode.SEMANTIC_SAFE

    /** Feeds one fresh [ChannelStats] sample and returns the (possibly unchanged) resulting mode. */
    fun update(stats: ChannelStats): ChannelMode {
        val smoothedLoss = lossEwma.update(stats.lossPct.toDouble())
        val smoothedRtt = rttEwma.update(stats.rttMs.toDouble())
        val smoothedGoodput = goodputEwma.update(stats.goodputBps.toDouble())

        // Thermal/battery are hard gates, not part of the smoothed hysteresis logic — a
        // device under real thermal pressure must never be pushed into the most expensive
        // mode regardless of how good the radio link looks (§29).
        val thermalForcesSafe = stats.thermalState == ThermalState.SEVERE ||
            stats.thermalState == ThermalState.CRITICAL
        val batteryForcesSafe = (stats.batteryPct ?: 100) <= 10

        if (thermalForcesSafe || batteryForcesSafe) {
            currentMode = ChannelMode.SEMANTIC_SAFE
            return currentMode
        }

        val linkIsGood = smoothedLoss <= upgradeLossPct &&
            smoothedRtt <= upgradeRttMs &&
            smoothedGoodput >= minGoodputForResidualBps
        val linkIsBad = smoothedLoss >= downgradeLossPct || smoothedRtt >= downgradeRttMs

        currentMode = when (currentMode) {
            ChannelMode.SEMANTIC_SAFE ->
                if (linkIsGood) ChannelMode.SEMANTIC_PLUS_RESIDUAL else ChannelMode.SEMANTIC_SAFE

            ChannelMode.SEMANTIC_PLUS_RESIDUAL ->
                when {
                    linkIsBad -> ChannelMode.SEMANTIC_SAFE
                    linkIsGood -> ChannelMode.HIGH_FIDELITY_RESIDUAL
                    else -> ChannelMode.SEMANTIC_PLUS_RESIDUAL
                }

            ChannelMode.HIGH_FIDELITY_RESIDUAL ->
                if (linkIsBad || !linkIsGood) {
                    // Any deterioration steps down exactly one level — never a hard drop
                    // straight to SEMANTIC_SAFE from the top mode on a single bad sample,
                    // since that IS the thrash pattern hysteresis exists to prevent. A
                    // sustained bad link will walk down one mode per update() call.
                    if (linkIsBad) ChannelMode.SEMANTIC_SAFE else ChannelMode.SEMANTIC_PLUS_RESIDUAL
                } else {
                    ChannelMode.HIGH_FIDELITY_RESIDUAL
                }
        }

        // Residual is meaningless while FeatureFlags.neuralResidualEnabled is false (§23 —
        // no trained model exists yet). The controller's mode still tracks link quality for
        // when it does exist, but callers must not attempt residual transmission based on
        // mode alone without also checking the flag.
        return currentMode
    }

    fun mode(): ChannelMode = currentMode
}
