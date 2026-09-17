#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# MyHomeLib requires Unicode-safe paths/text. Minimal Linux environments may start
# in the POSIX/ASCII locale even on Java 21, which leaves native.encoding unsafe.
# Prefer an installed UTF-8 C locale for Maven and all forked test JVMs.
if [[ "$(locale charmap 2>/dev/null || true)" != "UTF-8" ]]; then
  for candidate in C.UTF-8 C.utf8; do
    if locale -a 2>/dev/null | grep -Fxiq "$candidate"; then
      export LANG="$candidate"
      export LC_ALL="$candidate"
      break
    fi
  done
fi
MAVEN="$ROOT/.mvn/maven/apache-maven-3.9.6/bin/mvn"
REPO="$ROOT/.mvn/repository"
if [[ ! -x "$MAVEN" ]]; then
  echo "ERROR: bundled Maven 3.9.6 is missing: $MAVEN" >&2
  exit 127
fi
mkdir -p "$REPO"
exec "$MAVEN" -Dmaven.repo.local="$REPO" "$@"
