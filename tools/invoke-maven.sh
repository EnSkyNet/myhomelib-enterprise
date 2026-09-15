#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIN_MAVEN_MAJOR=3
MIN_MAVEN_MINOR=9
MIN_MAVEN_PATCH=6

MIN_JAVA_MAJOR=21

java_major() {
  local java_bin="${JAVA_HOME:+$JAVA_HOME/bin/java}"
  if [[ -n "${JAVA_HOME:-}" && -x "$java_bin" ]]; then
    "$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
  elif command -v java >/dev/null 2>&1; then
    java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
  fi
}

require_supported_java() {
  local major="$1"
  if [[ -z "$major" || ! "$major" =~ ^[0-9]+$ ]]; then
    echo "ERROR: Cannot determine Java version; MyHomeLib requires JDK 21+." >&2
    exit 126
  fi
  if (( major < MIN_JAVA_MAJOR )); then
    echo "ERROR: JDK $major is unsupported; MyHomeLib requires JDK 21+." >&2
    exit 126
  fi
}

require_supported_java "$(java_major)"

maven_version() {
  "$@" -version 2>/dev/null | sed -n 's/^Apache Maven \([0-9][0-9.]*\).*/\1/p' | head -n 1
}

require_supported_maven() {
  local version="$1"
  if [[ -z "$version" ]]; then
    echo "ERROR: Cannot determine Maven version; MyHomeLib requires Maven 3.9.6+." >&2
    exit 126
  fi
  local major=0 minor=0 patch=0 rest
  IFS=. read -r major minor patch rest <<<"$version"
  major=${major:-0}; minor=${minor:-0}; patch=${patch:-0}
  if (( major < MIN_MAVEN_MAJOR ||
        (major == MIN_MAVEN_MAJOR && minor < MIN_MAVEN_MINOR) ||
        (major == MIN_MAVEN_MAJOR && minor == MIN_MAVEN_MINOR && patch < MIN_MAVEN_PATCH) )); then
    echo "ERROR: Maven $version is unsupported; MyHomeLib requires Maven 3.9.6+." >&2
    exit 126
  fi
}

if [[ -x "$ROOT/mvnw" ]]; then
  version="$(maven_version "$ROOT/mvnw")"
  require_supported_maven "$version"
  exec "$ROOT/mvnw" "$@"
fi

if command -v mvn >/dev/null 2>&1; then
  MAVEN="$(command -v mvn)"
  version="$(maven_version "$MAVEN")"
  require_supported_maven "$version"
  exec "$MAVEN" "$@"
fi

cat >&2 <<'MSG'
ERROR: Maven is required to build MyHomeLib.
The formal source archive intentionally does not bundle Maven or Maven Wrapper.
Install Maven 3.9.6+ (or use a repository checkout that provides the wrapper) and retry.
MSG
exit 127
