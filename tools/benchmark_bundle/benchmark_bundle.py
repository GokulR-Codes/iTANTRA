#!/usr/bin/env python3
"""
benchmark_bundle.py — reads one or more telemetry JSONL files (schema:
benchmark/src/main/kotlin/com/itantra/benchmark/TelemetryEvent.kt) and
produces an aggregated markdown table: mean / median / p95 for each numeric
metric, split by device class and transport where present.

This tool only aggregates numbers that were actually written by someone —
it does not fill gaps, does not estimate, and reports a metric as "n=0, no
data" rather than silently omitting it if a field was never populated in the
input. That is a deliberate mirror of §1's evidence discipline: an empty
result must look empty, not get papered over.

Usage:
    python3 benchmark_bundle.py telemetry1.jsonl telemetry2.jsonl --out report.md
"""
from __future__ import annotations

import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path

NUMERIC_FIELDS = [
    "sttRtf",
    "ttsFirstAudioMs",
    "endToEndLatencyMs",
    "payloadBytes",
    "bitsPerSecond",
    "packetLossFraction",
    "rttMs",
    "batteryDrainMahPerHour",
]


def load_events(paths: list[Path]) -> list[dict]:
    events = []
    for p in paths:
        with p.open("r", encoding="utf-8") as f:
            for lineno, line in enumerate(f, start=1):
                line = line.strip()
                if not line:
                    continue
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError as e:
                    raise ValueError(f"{p}:{lineno}: malformed JSON line: {e}") from e
    return events


def percentile(values: list[float], p: float) -> float:
    if not values:
        raise ValueError("percentile of empty list")
    s = sorted(values)
    k = (len(s) - 1) * p
    f = int(k)
    c = min(f + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def summarize(values: list[float]) -> dict:
    return {
        "n": len(values),
        "mean": statistics.mean(values) if values else None,
        "median": statistics.median(values) if values else None,
        "p95": percentile(values, 0.95) if values else None,
        "min": min(values) if values else None,
        "max": max(values) if values else None,
    }


def render_markdown(events: list[dict]) -> str:
    lines = []
    lines.append("# Benchmark bundle report")
    lines.append("")
    lines.append(f"Generated from {len(events)} telemetry events.")
    lines.append("")

    groups: dict[tuple, list[dict]] = defaultdict(list)
    for e in events:
        key = (e.get("deviceClass"), e.get("transport"))
        groups[key].append(e)

    for (device_class, transport), group_events in sorted(groups.items(), key=lambda kv: (str(kv[0][0]), str(kv[0][1]))):
        header = f"## Device class {device_class or 'unknown'} / transport {transport or 'n/a'}"
        lines.append(header)
        lines.append("")
        lines.append(f"n = {len(group_events)} events")
        lines.append("")
        lines.append("| metric | n | mean | median | p95 | min | max |")
        lines.append("|---|---|---|---|---|---|---|")
        for field in NUMERIC_FIELDS:
            values = [e[field] for e in group_events if e.get(field) is not None]
            if not values:
                lines.append(f"| {field} | 0 | - | - | - | - | - |  (no data)")
                continue
            s = summarize(values)
            lines.append(
                f"| {field} | {s['n']} | {s['mean']:.4g} | {s['median']:.4g} | "
                f"{s['p95']:.4g} | {s['min']:.4g} | {s['max']:.4g} |"
            )
        lines.append("")

    lines.append("---")
    lines.append(
        "Every row above is computed from raw events in the input files — "
        "no value in this table is estimated or interpolated. Rows marked "
        "'(no data)' mean that field was never populated by an instrumented "
        "run, not that the true value is zero."
    )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", nargs="+", type=Path, help="one or more telemetry .jsonl files")
    parser.add_argument("--out", type=Path, default=None, help="write markdown report here (default: stdout)")
    args = parser.parse_args()

    for p in args.inputs:
        if not p.exists():
            raise SystemExit(f"error: input file not found: {p}")

    events = load_events(args.inputs)
    report = render_markdown(events)

    if args.out:
        args.out.write_text(report, encoding="utf-8")
        print(f"wrote {args.out} ({len(events)} events, {len(report.splitlines())} lines)")
    else:
        print(report)


if __name__ == "__main__":
    main()
