#!/usr/bin/env python3
"""Fail CI when JavaFX test selection silently executes zero tests."""
from __future__ import annotations

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", type=Path)
    parser.add_argument("--min-tests", type=int, default=1)
    parser.add_argument("--min-suites", type=int, default=1)
    args = parser.parse_args()

    total = failures = errors = skipped = 0
    suites = []
    if args.reports.is_dir():
        candidates = sorted(args.reports.glob("TEST-*.xml"))
    else:
        candidates = [args.reports] if args.reports.exists() else []

    for report in candidates:
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            continue
        name = root.attrib.get("name", report.stem)
        if not name.endswith("FxTest"):
            continue
        tests = int(root.attrib.get("tests", "0") or 0)
        if tests <= 0:
            continue
        suites.append((name, tests))
        total += tests
        failures += int(root.attrib.get("failures", "0") or 0)
        errors += int(root.attrib.get("errors", "0") or 0)
        skipped += int(root.attrib.get("skipped", "0") or 0)

    if total < args.min_tests or len(suites) < args.min_suites:
        print(
            "FX_TEST_GATE_FAILED: "
            f"executed={total}, required_tests>={args.min_tests}, "
            f"suites={len(suites)}, required_suites>={args.min_suites}, reports={args.reports}"
        )
        return 2
    if failures or errors:
        print(f"FX_TEST_GATE_FAILED: tests={total}, failures={failures}, errors={errors}, skipped={skipped}")
        return 3
    print(f"FX_TEST_GATE_OK: tests={total}, skipped={skipped}, suites={len(suites)}")
    for name, tests in suites:
        print(f" - {name}: {tests}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
