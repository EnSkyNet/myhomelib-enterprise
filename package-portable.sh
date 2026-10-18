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
./package-desktop.sh app-image

case "$(uname -s)" in
  Darwin) PLATFORM=macos; IMAGE="dist/MyHomeLib.app" ;;
  Linux)  PLATFORM=linux; IMAGE="dist/MyHomeLib" ;;
  *) echo "Unsupported Unix platform: $(uname -s)" >&2; exit 2 ;;
esac
ARCH=$(uname -m | tr '[:upper:]' '[:lower:]')
[[ -d "$IMAGE" ]] || { echo "jpackage app-image not found: $IMAGE" >&2; exit 1; }
case "$PLATFORM" in
  linux) LAUNCHER="$IMAGE/bin/MyHomeLib" ;;
  macos) LAUNCHER="$IMAGE/Contents/MacOS/MyHomeLib" ;;
  *) echo "Unsupported platform for portable launcher marker: $PLATFORM" >&2; exit 2 ;;
esac
[[ -x "$LAUNCHER" ]] || { echo "jpackage launcher not found: $LAUNCHER" >&2; exit 1; }
# Make the versioned archive genuinely portable immediately after extraction.
# AppPaths resolves the marker relative to this native launcher, not the cwd.
: > "$(dirname "$LAUNCHER")/myhomelib2.ini"
ARCHIVE="dist/myhomelib-${VERSION}-${PLATFORM}-${ARCH}.tar.gz"
rm -f "$ARCHIVE"
tar -C dist -czf "$ARCHIVE" "$(basename "$IMAGE")"
echo "Portable desktop archive: $ARCHIVE"
