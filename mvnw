#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAVEN="$ROOT/.mvn/maven/apache-maven-3.9.6/bin/mvn"
REPO="$ROOT/.mvn/repository"
if [[ ! -x "$MAVEN" ]]; then
  echo "ERROR: bundled Maven 3.9.6 is missing: $MAVEN" >&2
  exit 127
fi
mkdir -p "$REPO"
exec "$MAVEN" -Dmaven.repo.local="$REPO" "$@"
