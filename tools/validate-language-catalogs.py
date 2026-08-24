#!/usr/bin/env python3
"""Validate MyHomeLib file-based UI language catalogues."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LANG_DIR = ROOT / "Lang"
CODE_RE = re.compile(r"^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$")


def main() -> int:
    errors: list[str] = []
    seen: dict[str, Path] = {}
    catalogs: dict[str, dict[str, str]] = {}

    for path in sorted(LANG_DIR.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"{path}: invalid JSON/UTF-8: {exc}")
            continue
        code = str(data.get("code", "")).strip().lower().replace("_", "-")
        name = str(data.get("name", "")).strip()
        translations = data.get("translations")
        if not CODE_RE.fullmatch(code):
            errors.append(f"{path}: invalid code {code!r}")
        if not name:
            errors.append(f"{path}: name is required")
        if not isinstance(translations, dict):
            errors.append(f"{path}: translations must be an object")
            continue
        if code in seen:
            errors.append(f"{path}: duplicate code {code!r}, first seen in {seen[code]}")
        seen[code] = path
        catalogs[code] = {str(k): str(v) for k, v in translations.items()}

    if not catalogs:
        errors.append(f"{LANG_DIR}: no language catalogues found")

    bundled_dir = ROOT / "myhomelib-ui" / "src" / "main" / "resources" / "lang" / "default"
    for code in ("uk", "en", "bg"):
        external = LANG_DIR / f"{code}.json"
        bundled = bundled_dir / f"{code}.json"
        if external.is_file() and bundled.is_file() and external.read_bytes() != bundled.read_bytes():
            errors.append(f"{code}: root Lang catalogue differs from bundled first-run copy")

    # Shipped catalogues intentionally share a common key set. External catalogues
    # may contain any subset; missing keys fall back to Ukrainian source text.
    shipped = [catalogs[c] for c in ("uk", "en", "bg") if c in catalogs]
    if len(shipped) == 3:
        keysets = [set(x) for x in shipped]
        if not (keysets[0] == keysets[1] == keysets[2]):
            errors.append("shipped uk/en/bg catalogues do not have the same translation keys")

    if errors:
        print("Language catalogue validation FAILED:")
        for error in errors:
            print(" -", error)
        return 1

    print("Language catalogue validation OK")
    for code, path in seen.items():
        print(f" - {code}: {path.name}, {len(catalogs[code])} keys")
    return 0


if __name__ == "__main__":
    sys.exit(main())
