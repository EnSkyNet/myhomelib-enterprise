#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -x "$ROOT/mvnw" ]]; then
  exec "$ROOT/mvnw" "$@"
fi

if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

cat >&2 <<'MSG'
ERROR: Maven is required to build MyHomeLib.
The formal source archive intentionally does not bundle Maven or Maven Wrapper.
Install Maven 3.9.6+ (or use a repository checkout that provides the wrapper) and retry.
MSG
exit 127
