#!/usr/bin/env python3
"""
model_scorecard.py — generates a blank Model Scorecard template, and
validates a filled one has no placeholder/missing required fields.

Purpose (§18): "no model may be adopted without a filled Model Scorecard."
This tool makes that rule mechanically checkable instead of a matter of
someone remembering to fill in a doc. `validate` exits non-zero (suitable
for a CI gate) if any required field is missing or still holds the
placeholder value "TBD".

Usage:
    python3 model_scorecard.py new --model-name indicconformer-hi --out scorecards/indicconformer-hi.md
    python3 model_scorecard.py validate scorecards/indicconformer-hi.md
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REQUIRED_FIELDS = [
    "Model name",
    "Task",  # STT | TTS | LID
    "Language(s)",
    "Source / provenance",
    "License",
    "License compatible with SIH submission",  # yes/no + one-line why
    "Parameter count / disk size (MB)",
    "Quantization applied",
    "Device class tested on",
    "RTF measured",
    "Accuracy metric measured",  # WER for STT, MOS/intelligibility for TTS, accuracy for LID
    "Measured on date",
    "Measured by",
    "Benchmark raw data location",  # path to the telemetry/eval artifact backing the numbers above
    "Decision",  # adopted / rejected / needs-more-data
    "Decision rationale",
]

PLACEHOLDER_VALUE = "TBD"


def render_template(model_name: str) -> str:
    lines = [f"# Model Scorecard: {model_name}", ""]
    lines.append(
        "Every field below is required before this model may be adopted (§18). "
        "Leaving a field as `TBD` is a valid *in-progress* state, but "
        "`model_scorecard.py validate` will reject the scorecard until every "
        "field has a real value — that check is what actually enforces the rule."
    )
    lines.append("")
    for field in REQUIRED_FIELDS:
        lines.append(f"- **{field}**: {PLACEHOLDER_VALUE}")
    lines.append("")
    return "\n".join(lines)


def parse_scorecard(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    pattern = re.compile(r"^\s*-\s*\*\*(.+?)\*\*:\s*(.*)$")
    for line in text.splitlines():
        m = pattern.match(line)
        if m:
            values[m.group(1).strip()] = m.group(2).strip()
    return values


def validate(text: str) -> list[str]:
    values = parse_scorecard(text)
    problems = []
    for field in REQUIRED_FIELDS:
        if field not in values:
            problems.append(f"missing field: '{field}'")
        elif values[field] == "" or values[field] == PLACEHOLDER_VALUE:
            problems.append(f"field not filled in: '{field}' (still '{PLACEHOLDER_VALUE}' or empty)")
    return problems


def cmd_new(args: argparse.Namespace) -> None:
    template = render_template(args.model_name)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(template, encoding="utf-8")
        print(f"wrote {args.out}")
    else:
        print(template)


def cmd_validate(args: argparse.Namespace) -> None:
    text = args.scorecard.read_text(encoding="utf-8")
    problems = validate(text)
    if problems:
        print(f"INVALID: {args.scorecard} is not a complete Model Scorecard ({len(problems)} problem(s)):")
        for p in problems:
            print(f"  - {p}")
        sys.exit(1)
    print(f"VALID: {args.scorecard} has all {len(REQUIRED_FIELDS)} required fields filled in.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p_new = sub.add_parser("new", help="generate a blank scorecard template")
    p_new.add_argument("--model-name", required=True)
    p_new.add_argument("--out", type=Path, default=None)
    p_new.set_defaults(func=cmd_new)

    p_validate = sub.add_parser("validate", help="check a scorecard has no missing/placeholder fields")
    p_validate.add_argument("scorecard", type=Path)
    p_validate.set_defaults(func=cmd_validate)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
