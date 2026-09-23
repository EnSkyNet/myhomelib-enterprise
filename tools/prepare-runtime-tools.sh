#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EPUB="$ROOT/tools/validators/epubcheck"
LIBMOBI="$ROOT/tools/converters/libmobi"
SRC="$LIBMOBI/build-src"
BUILD="$LIBMOBI/build-native"
OUT="$LIBMOBI/mobitool"

[[ -s "$EPUB/epubcheck.jar" ]] || { echo "ERROR: EPUBCheck JAR missing" >&2; exit 2; }
[[ -d "$EPUB/lib" ]] || { echo "ERROR: EPUBCheck lib directory missing" >&2; exit 2; }
java -jar "$EPUB/epubcheck.jar" --version | grep -F "EPUBCheck v5.4.0" >/dev/null

if [[ ! -x "$OUT" ]] || ! "$OUT" -v 2>/dev/null | grep -F "libmobi: 0.12" >/dev/null; then
  command -v cmake >/dev/null 2>&1 || { echo "ERROR: CMake is required to build mobitool" >&2; exit 2; }
  rm -rf "$BUILD"
  cmake -S "$SRC" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release
  cmake --build "$BUILD" --config Release --parallel
  CANDIDATE="$BUILD/mobitool"
  [[ -x "$CANDIDATE" ]] || { echo "ERROR: mobitool was not produced: $CANDIDATE" >&2; exit 2; }
  cp "$CANDIDATE" "$OUT"
  chmod 0755 "$OUT"
fi

"$OUT" -v | grep -F "libmobi: 0.12" >/dev/null
echo "Runtime tools ready: EPUBCheck 5.4.0, libmobi 0.12"
