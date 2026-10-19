#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
DIR=${1:-dist}
[ -d "$DIR" ] || { echo "Directory not found: $DIR" >&2; exit 1; }

# Python stdlib keeps checksum generation identical on Linux and macOS and avoids
# GNU-only find/sort/sha256sum flags. Paths in SHA256SUMS are always relative,
# slash-separated and sorted deterministically.
python3 - "$DIR" <<'PY'
from __future__ import annotations
import hashlib
import os
from pathlib import Path
import sys

base = Path(sys.argv[1]).resolve()
out = base / "SHA256SUMS"
tmp = base / ".SHA256SUMS.tmp"

files = sorted(
    (p for p in base.rglob("*") if p.is_file() and p.name not in {"SHA256SUMS", ".SHA256SUMS.tmp"}),
    key=lambda p: p.relative_to(base).as_posix(),
)

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

try:
    with tmp.open("w", encoding="utf-8", newline="\n") as stream:
        for path in files:
            rel = path.relative_to(base).as_posix()
            stream.write(f"{digest(path)}  {rel}\n")
    os.replace(tmp, out)
finally:
    try:
        tmp.unlink()
    except FileNotFoundError:
        pass

print(f"Created {out} with {len(files)} entries")
PY
