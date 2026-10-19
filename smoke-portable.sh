#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

VERSION=${MHL_VERSION:-$(python3 - <<'PY'
import xml.etree.ElementTree as ET
root=ET.parse('pom.xml').getroot()
ns={'m':'http://maven.apache.org/POM/4.0.0'}
print(root.findtext('m:version', namespaces=ns) or '')
PY
)}
[[ -n "$VERSION" ]] || { echo "Cannot determine version from pom.xml" >&2; exit 2; }

case "$(uname -s)" in
  Darwin) PLATFORM=macos; PATTERN="dist/myhomelib-${VERSION}-macos-*.tar.gz" ;;
  Linux)  PLATFORM=linux; PATTERN="dist/myhomelib-${VERSION}-linux-*.tar.gz" ;;
  *) echo "Unsupported Unix platform: $(uname -s)" >&2; exit 2 ;;
esac

shopt -s nullglob
archives=($PATTERN)
shopt -u nullglob
[[ ${#archives[@]} -eq 1 ]] || {
  echo "Expected exactly one portable archive matching $PATTERN, found ${#archives[@]}" >&2
  exit 2
}
archive=${archives[0]}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/myhomelib-portable-smoke.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/extract" "$tmp/home" "$tmp/cwd"
tar -xzf "$archive" -C "$tmp/extract"

case "$PLATFORM" in
  linux) launcher="$tmp/extract/MyHomeLib/bin/MyHomeLib" ;;
  macos) launcher="$tmp/extract/MyHomeLib.app/Contents/MacOS/MyHomeLib" ;;
esac
[[ -x "$launcher" ]] || { echo "Extracted launcher is missing/not executable: $launcher" >&2; exit 1; }
launcher_dir=$(dirname "$launcher")
marker="$launcher_dir/myhomelib2.ini"
[[ -f "$marker" ]] || { echo "Portable marker is missing beside launcher: $marker" >&2; exit 1; }

output=$(cd "$tmp/cwd" && JAVA_TOOL_OPTIONS="-Duser.home=$tmp/home" "$launcher" --release-smoke 2>&1)
printf '%s\n' "$output"
grep -q 'MYHOMELIB_RELEASE_SMOKE_OK' <<<"$output"
[[ -d "$launcher_dir/data" ]] || { echo "Portable data directory was not created beside launcher" >&2; exit 1; }
[[ ! -e "$tmp/home/.myhomelibcorp" ]] || { echo "Portable launch wrote to the user profile" >&2; exit 1; }

echo "Extracted portable archive smoke: PASS ($archive)"
