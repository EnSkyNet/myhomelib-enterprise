#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
LOG="$ROOT/PREPARE-OFFLINE-REPO.log"
ZIP="$ROOT/maven-offline-repo-upgraded.zip"
REPO="$ROOT/.mvn/repository"

exec > >(tee "$LOG") 2>&1

echo "MyHomeLib upgraded dependency preparation"
echo "Project: $ROOT"
java -version
if ! java -version 2>&1 | head -1 | grep -Eq 'version "21([._]|\")'; then
  echo "ERROR: JDK 21 is required." >&2
  exit 2
fi

echo "=== Online clean verify ==="
./mvnw -B -ntp -U clean verify

echo "=== dependency:go-offline ==="
./mvnw -B -ntp -U dependency:go-offline

echo "=== Offline clean verify ==="
./mvnw -o -B -ntp clean verify

find "$REPO" -type f \( -name '*.lastUpdated' -o -name '*.tmp' -o -name '*.part' \) -delete || true
rm -f "$ZIP" "$ZIP.sha256.txt"
python3 - "$REPO" "$ZIP" <<'PY'
import os, sys, zipfile
repo, out = sys.argv[1:]
base = os.path.dirname(repo)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, _, files in os.walk(repo):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, base))
PY
sha256sum "$ZIP" > "$ZIP.sha256.txt"
echo "SUCCESS: $ZIP"
