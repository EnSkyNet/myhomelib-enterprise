#!/usr/bin/env python3
"""Ratchet production Java empty catch blocks.

Existing empty catches are audited as legacy/intentional fallback points. This gate does not
claim they are all desirable; it prevents their count from silently growing per file/exception
signature. Removing an empty catch is always allowed. Adding/increasing one requires an explicit
baseline update together with code review.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "tools" / "empty-catch-baseline.json"


def production_java() -> list[Path]:
    return sorted(
        p for p in ROOT.glob("myhomelib-*/src/main/java/**/*.java")
        if "target" not in p.parts
    )


def _mask_noncode(text: str) -> str:
    """Blank Java comments/string/char literals while preserving offsets/newlines."""
    out = list(text)
    i = 0
    quote = None
    escaped = False
    line_comment = False
    block_comment = False
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ""
        if line_comment:
            if c == "\n":
                line_comment = False
            else:
                out[i] = " "
            i += 1
            continue
        if block_comment:
            if c == "*" and n == "/":
                out[i] = out[i + 1] = " "
                block_comment = False
                i += 2
                continue
            if c != "\n":
                out[i] = " "
            i += 1
            continue
        if quote is not None:
            if c != "\n":
                out[i] = " "
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == quote:
                quote = None
            i += 1
            continue
        if c == "/" and n == "/":
            out[i] = out[i + 1] = " "
            line_comment = True
            i += 2
            continue
        if c == "/" and n == "*":
            out[i] = out[i + 1] = " "
            block_comment = True
            i += 2
            continue
        if c in ('"', "'"):
            out[i] = " "
            quote = c
        i += 1
    return "".join(out)


def _catch_blocks(text: str):
    masked = _mask_noncode(text)
    for match in re.finditer(r"\bcatch\s*\(([^)]*)\)\s*\{", masked):
        start = match.end()
        depth = 1
        i = start
        while i < len(masked) and depth:
            c = masked[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            i += 1
        if depth == 0:
            # The declaration is taken from original source so exception types/names are retained.
            declaration = text[match.start(1):match.end(1)]
            yield declaration, text[start:i - 1]


def _code_only(body: str) -> str:
    return _mask_noncode(body).strip()


def counts() -> Counter[str]:
    result: Counter[str] = Counter()
    for path in production_java():
        rel = path.relative_to(ROOT).as_posix()
        text = path.read_text(encoding="utf-8-sig")
        for declaration, body in _catch_blocks(text):
            if _code_only(body):
                continue
            normalized = " ".join(declaration.split())
            result[f"{rel}|{normalized}"] += 1
    return result


def write_baseline(current: Counter[str]) -> None:
    payload = {
        "schema": 1,
        "description": "Maximum audited production empty-catch count per file/catch declaration.",
        "counts": dict(sorted(current.items())),
        "total": sum(current.values()),
    }
    BASELINE.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"EMPTY CATCH BASELINE WRITTEN: {payload['total']} blocks / {len(current)} signatures")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-baseline", action="store_true")
    args = parser.parse_args()
    current = counts()
    if args.write_baseline:
        write_baseline(current)
        return 0
    if not BASELINE.exists():
        print(f"ERROR: missing baseline {BASELINE.relative_to(ROOT)}")
        return 2
    baseline_doc = json.loads(BASELINE.read_text(encoding="utf-8"))
    baseline = Counter({str(k): int(v) for k, v in baseline_doc.get("counts", {}).items()})
    regressions = []
    for key, value in sorted(current.items()):
        allowed = baseline.get(key, 0)
        if value > allowed:
            regressions.append((key, value, allowed))
    if regressions:
        print("EMPTY CATCH GROWTH CHECK: FAIL")
        for key, value, allowed in regressions:
            print(f"  {key}: {value} > audited baseline {allowed}")
        return 1
    removed = sum(max(0, baseline[k] - current.get(k, 0)) for k in baseline)
    print(f"EMPTY CATCH GROWTH CHECK: PASS; current={sum(current.values())}, baseline={sum(baseline.values())}, removed={removed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
