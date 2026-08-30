#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
VERSION=${MHL_VERSION:-7.1.0}
./package-desktop.sh app-image

case "$(uname -s)" in
  Darwin) PLATFORM=macos; IMAGE="dist/MyHomeLib.app" ;;
  Linux)  PLATFORM=linux; IMAGE="dist/MyHomeLib" ;;
  *) echo "Unsupported Unix platform: $(uname -s)" >&2; exit 2 ;;
esac
ARCH=$(uname -m | tr '[:upper:]' '[:lower:]')
[[ -d "$IMAGE" ]] || { echo "jpackage app-image not found: $IMAGE" >&2; exit 1; }
ARCHIVE="dist/myhomelib-${VERSION}-${PLATFORM}-${ARCH}.tar.gz"
rm -f "$ARCHIVE"
tar -C dist -czf "$ARCHIVE" "$(basename "$IMAGE")"
echo "Portable desktop archive: $ARCHIVE"
