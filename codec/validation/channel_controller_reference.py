"""
Mirrors codec/src/main/kotlin/itantra/codec/ChannelController.kt (§27).
Executed here for the same reason as protocol_reference.py: no
kotlinc/gradle in this sandbox, and Prime Directive #2 forbids treating
an unexecuted Kotlin test as evidence.

Run: python3 channel_controller_reference.py
"""
from __future__ import annotations
from dataclasses import dataclass
from enum import Enum, auto


class Mode(Enum):
    SEMANTIC_SAFE = auto()
    SEMANTIC_PLUS_RESIDUAL = auto()
    HIGH_FIDELITY_RESIDUAL = auto()


class Thermal(Enum):
    NOMINAL = auto()
    WARM = auto()
    THROTTLING = auto()
    CRITICAL = auto()


@dataclass
class Stats:
    rtt_ms: float
    loss_pct: float
    goodput_bps: float
    thermal: Thermal = Thermal.NOMINAL
    battery_pct: int = 100


class Ewma:
    def __init__(self, alpha=0.3):
        self.alpha = alpha
        self.value = None

    def update(self, sample):
        self.value = sample if self.value is None else self.alpha * sample + (1 - self.alpha) * self.value
        return self.value


class ChannelController:
    def __init__(self, downgrade_loss=8.0, upgrade_loss=3.0, downgrade_rtt=400.0,
                 upgrade_rtt=150.0, min_goodput=8000.0, alpha=0.3):
        assert upgrade_loss < downgrade_loss
        assert upgrade_rtt < downgrade_rtt
        self.downgrade_loss = downgrade_loss
        self.upgrade_loss = upgrade_loss
        self.downgrade_rtt = downgrade_rtt
        self.upgrade_rtt = upgrade_rtt
        self.min_goodput = min_goodput
        self.loss_ewma = Ewma(alpha)
        self.rtt_ewma = Ewma(alpha)
        self.goodput_ewma = Ewma(alpha)
        self.mode = Mode.SEMANTIC_SAFE

    def update(self, s: Stats) -> Mode:
        loss = self.loss_ewma.update(s.loss_pct)
        rtt = self.rtt_ewma.update(s.rtt_ms)
        goodput = self.goodput_ewma.update(s.goodput_bps)

        if s.thermal in (Thermal.THROTTLING, Thermal.CRITICAL) or s.battery_pct <= 10:
            self.mode = Mode.SEMANTIC_SAFE
            return self.mode

        link_good = loss <= self.upgrade_loss and rtt <= self.upgrade_rtt and goodput >= self.min_goodput
        link_bad = loss >= self.downgrade_loss or rtt >= self.downgrade_rtt

        if self.mode == Mode.SEMANTIC_SAFE:
            self.mode = Mode.SEMANTIC_PLUS_RESIDUAL if link_good else Mode.SEMANTIC_SAFE
        elif self.mode == Mode.SEMANTIC_PLUS_RESIDUAL:
            if link_bad:
                self.mode = Mode.SEMANTIC_SAFE
            elif link_good:
                self.mode = Mode.HIGH_FIDELITY_RESIDUAL
        elif self.mode == Mode.HIGH_FIDELITY_RESIDUAL:
            if link_bad:
                self.mode = Mode.SEMANTIC_SAFE
            elif not link_good:
                self.mode = Mode.SEMANTIC_PLUS_RESIDUAL
        return self.mode


def run_checks():
    results = []

    def check(name, cond, detail=""):
        results.append((name, bool(cond), detail))

    # Scenario 1: consistently excellent link should climb all the way up.
    c = ChannelController()
    order = {Mode.SEMANTIC_SAFE: 0, Mode.SEMANTIC_PLUS_RESIDUAL: 1, Mode.HIGH_FIDELITY_RESIDUAL: 2}
    modes = []
    for _ in range(15):
        modes.append(c.update(Stats(rtt_ms=50, loss_pct=0.5, goodput_bps=50_000)))
    check("excellent link climbs to HIGH_FIDELITY_RESIDUAL within 15 samples",
          modes[-1] == Mode.HIGH_FIDELITY_RESIDUAL, f"final={modes[-1]}")
    check("mode is monotonically non-decreasing while link stays excellent",
          all(order[modes[i]] <= order[modes[i + 1]] for i in range(len(modes) - 1)))

    # Scenario 2: link degrades suddenly from HIGH_FIDELITY_RESIDUAL -> steps down one level at a time, not straight to SAFE.
    c2 = ChannelController()
    for _ in range(15):
        c2.update(Stats(rtt_ms=50, loss_pct=0.5, goodput_bps=50_000))
    assert c2.mode == Mode.HIGH_FIDELITY_RESIDUAL, "setup precondition failed"
    m_after_1_bad_sample = c2.update(Stats(rtt_ms=500, loss_pct=15, goodput_bps=1000))
    check("one bad sample from HIGH_FIDELITY steps down exactly one level (not straight to SAFE)",
          m_after_1_bad_sample == Mode.SEMANTIC_PLUS_RESIDUAL,
          f"got {m_after_1_bad_sample} (EWMA smoothing should prevent single-sample overreaction)")

    # Scenario 3: thermal throttling forces SAFE immediately regardless of link quality.
    c3 = ChannelController()
    for _ in range(15):
        c3.update(Stats(rtt_ms=50, loss_pct=0.5, goodput_bps=50_000))
    assert c3.mode == Mode.HIGH_FIDELITY_RESIDUAL
    m = c3.update(Stats(rtt_ms=50, loss_pct=0.5, goodput_bps=50_000, thermal=Thermal.THROTTLING))
    check("thermal throttling forces SEMANTIC_SAFE even with an excellent link",
          m == Mode.SEMANTIC_SAFE, f"got {m}")

    # Scenario 4: low battery forces SAFE.
    c4 = ChannelController()
    for _ in range(15):
        c4.update(Stats(rtt_ms=50, loss_pct=0.5, goodput_bps=50_000))
    m = c4.update(Stats(rtt_ms=50, loss_pct=0.5, goodput_bps=50_000, battery_pct=5))
    check("battery <=10% forces SEMANTIC_SAFE even with an excellent link",
          m == Mode.SEMANTIC_SAFE, f"got {m}")

    # Scenario 5: noisy-but-good-on-average link should NOT thrash every sample.
    c5 = ChannelController()
    import random
    random.seed(42)
    modes5 = []
    for _ in range(40):
        # loss oscillates around a good average with noise
        noisy_loss = max(0.0, random.gauss(1.5, 2.0))
        modes5.append(c5.update(Stats(rtt_ms=60, loss_pct=noisy_loss, goodput_bps=40_000)))
    transitions = sum(1 for i in range(len(modes5) - 1) if modes5[i] != modes5[i + 1])
    check("noisy-but-good link makes a bounded number of transitions (not one per sample)",
          transitions < 10, f"transitions={transitions} out of 39 updates")

    return results


if __name__ == "__main__":
    results = run_checks()
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"channel_controller_reference.py — {passed}/{len(results)} checks passed\n")
    for name, ok, detail in results:
        status = "PASS" if ok else "FAIL"
        line = f"[{status}] {name}"
        if detail:
            line += f"  ({detail})"
        print(line)
    if passed != len(results):
        raise SystemExit(1)
