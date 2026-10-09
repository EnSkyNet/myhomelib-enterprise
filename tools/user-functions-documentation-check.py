#!/usr/bin/env python3
"""Fail-closed link between the UI action inventory and the canonical user-function catalogue."""
from __future__ import annotations
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "docs/current/UI-ACTION-COVERAGE.json"
CATALOG = ROOT / "docs/current/USER-FUNCTIONS-UA.md"
MARKER_RE = re.compile(r"<!--\s*function-ref:([a-z0-9-]+)\s*-->")


def main() -> int:
    try:
        payload = json.loads(REGISTRY.read_text(encoding="utf-8"))
        text = CATALOG.read_text(encoding="utf-8")
        markers = set(MARKER_RE.findall(text))
        if not markers:
            raise AssertionError("USER-FUNCTIONS-UA.md has no function-ref markers")
        entries = payload.get("entries", [])
        missing_ref = [e.get("key", "?") for e in entries if not e.get("user_function_ref")]
        if missing_ref:
            raise AssertionError(f"UI actions without user_function_ref: {missing_ref[:10]}")
        invalid = sorted({e["user_function_ref"] for e in entries if e["user_function_ref"] not in markers})
        if invalid:
            raise AssertionError(f"UI actions reference undocumented function flows: {invalid}")
        used = {e["user_function_ref"] for e in entries}
        unused = sorted(markers - used)
        # Non-desktop protocol flow is intentionally not a JavaFX action surface.
        allowed_unused = {"mcp", "non-desktop"}
        unexpected_unused = [x for x in unused if x not in allowed_unused]
        if unexpected_unused:
            raise AssertionError(f"documented UI flow has no mapped action: {unexpected_unused}")
        print(f"PASS: user-function documentation links {len(entries)}/{len(entries)} UI actions to {len(used)} documented flows")
        return 0
    except Exception as exc:
        print(f"FAIL: {exc}")
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
