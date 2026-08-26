#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -x "dist/MyHomeLib/bin/MyHomeLib" ]]; then
  LAUNCHER="dist/MyHomeLib/bin/MyHomeLib"
elif [[ -x "dist/MyHomeLib.app/Contents/MacOS/MyHomeLib" ]]; then
  LAUNCHER="dist/MyHomeLib.app/Contents/MacOS/MyHomeLib"
else
  echo "Packaged MyHomeLib launcher not found" >&2
  exit 2
fi
OUTPUT=$("$LAUNCHER" --release-smoke 2>&1)
printf '%s\n' "$OUTPUT"
grep -q 'MYHOMELIB_RELEASE_SMOKE_OK' <<<"$OUTPUT"
echo "Desktop packaged-launcher smoke: PASS"
