#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
DIR=${1:-dist}
[ -d "$DIR" ] || { echo "Directory not found: $DIR" >&2; exit 1; }
OUT="$DIR/SHA256SUMS"
find "$DIR" -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > "$OUT"
echo "Wrote $OUT"
