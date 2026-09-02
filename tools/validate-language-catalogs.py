#!/usr/bin/env python3
"""Validate MyHomeLib schema-versioned file-based UI/genre language catalogues."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LANG_DIR = ROOT / "Lang"
CODE_RE = re.compile(r"^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$")
CURRENT_SCHEMA = 3


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []
    seen: dict[str, Path] = {}
    catalogs: dict[str, dict] = {}

    for path in sorted(LANG_DIR.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"{path}: invalid JSON/UTF-8: {exc}")
            continue
        code = str(data.get("code", "")).strip().lower().replace("_", "-")
        name = str(data.get("name", "")).strip()
        version = data.get("schemaVersion", 1)
        translations = data.get("translations")
        genres = data.get("genres", {})
        if not CODE_RE.fullmatch(code): errors.append(f"{path}: invalid code {code!r}")
        if not name: errors.append(f"{path}: name is required")
        if not isinstance(version, int): errors.append(f"{path}: schemaVersion must be an integer")
        elif version > CURRENT_SCHEMA: errors.append(f"{path}: unsupported schemaVersion={version}, current={CURRENT_SCHEMA}")
        elif version < CURRENT_SCHEMA: warnings.append(f"{path}: legacy schemaVersion={version}; runtime fallback is supported")
        if not isinstance(translations, dict): errors.append(f"{path}: translations must be an object"); continue
        if not isinstance(genres, dict): errors.append(f"{path}: genres must be an object when present"); continue
        if code in seen: errors.append(f"{path}: duplicate code {code!r}, first seen in {seen[code]}")
        seen[code] = path
        catalogs[code] = {
            "translations": {str(k): str(v) for k, v in translations.items()},
            "genres": {str(k): str(v) for k, v in genres.items()},
            "schemaVersion": version,
        }

    if not catalogs: errors.append(f"{LANG_DIR}: no language catalogues found")

    bundled_dir = ROOT / "myhomelib-ui" / "src" / "main" / "resources" / "lang" / "default"
    for code in ("uk", "en", "bg"):
        external = LANG_DIR / f"{code}.json"
        bundled = bundled_dir / f"{code}.json"
        if external.is_file() and bundled.is_file() and external.read_bytes() != bundled.read_bytes():
            errors.append(f"{code}: root Lang catalogue differs from bundled first-run copy")

    shipped = [catalogs[c] for c in ("uk", "en", "bg") if c in catalogs]
    if len(shipped) == 3:
        ui_sets = [set(x["translations"]) for x in shipped]
        genre_sets = [set(x["genres"]) for x in shipped]
        if not (ui_sets[0] == ui_sets[1] == ui_sets[2]): errors.append("shipped uk/en/bg catalogues do not have the same translation keys")
        if not (genre_sets[0] == genre_sets[1] == genre_sets[2]): errors.append("shipped uk/en/bg catalogues do not have the same stable genre keys")
        if any(x["schemaVersion"] != CURRENT_SCHEMA for x in shipped): errors.append("shipped catalogues must use current schemaVersion")
        if len(genre_sets[0]) < 25: errors.append("shipped catalogues have insufficient genre localization coverage")

    if errors:
        print("Language catalogue validation FAILED:")
        for error in errors: print(" -", error)
        return 1

    print("Language catalogue validation OK")
    for code, path in seen.items():
        c = catalogs[code]
        print(f" - {code}: {path.name}, schema={c['schemaVersion']}, {len(c['translations'])} UI keys, {len(c['genres'])} genre keys")
    for warning in warnings: print(" WARN", warning)
    return 0


if __name__ == "__main__":
    sys.exit(main())
