#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EPUB="$ROOT/tools/validators/epubcheck"
LIBMOBI="$ROOT/tools/converters/libmobi"
SRC="$LIBMOBI/build-src"
BUILD="$LIBMOBI/build-native"
OUT="$LIBMOBI/mobitool"
REQUIRE_LIBMOBI="${MHL_REQUIRE_LIBMOBI:-0}"

[[ -s "$EPUB/epubcheck.jar" ]] || { echo "ERROR: EPUBCheck JAR missing" >&2; exit 2; }
[[ -d "$EPUB/lib" ]] || { echo "ERROR: EPUBCheck lib directory missing" >&2; exit 2; }
java -jar "$EPUB/epubcheck.jar" --version | grep -F "EPUBCheck v5.4.0" >/dev/null

libmobi_ready() {
  [[ -x "$OUT" ]] && "$OUT" -v 2>/dev/null | grep -F "libmobi: 0.12" >/dev/null
}

if ! libmobi_ready; then
  if [[ -f "$SRC/CMakeLists.txt" ]]; then
    command -v cmake >/dev/null 2>&1 || { echo "ERROR: CMake is required to build mobitool" >&2; exit 2; }
    rm -rf "$BUILD"
    cmake -S "$SRC" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release
    cmake --build "$BUILD" --config Release --parallel
    CANDIDATE="$BUILD/mobitool"
    [[ -x "$CANDIDATE" ]] || { echo "ERROR: mobitool was not produced: $CANDIDATE" >&2; exit 2; }
    cp "$CANDIDATE" "$OUT"
    chmod 0755 "$OUT"
  elif [[ "$REQUIRE_LIBMOBI" == "1" ]]; then
    echo "ERROR: bundled libmobi 0.12 source/runtime is missing: $SRC" >&2
    echo "       Full release packaging requires libmobi; restore tools/converters/libmobi/build-src." >&2
    exit 2
  else
    echo "WARNING: bundled libmobi 0.12 source/runtime is absent; desktop packaging will continue without bundled mobitool." >&2
    echo "         MOBI/AZW conversion remains available when a compatible mobitool is installed/configured at runtime." >&2
  fi
fi

if libmobi_ready; then
  echo "Runtime tools ready: EPUBCheck 5.4.0, libmobi 0.12"
else
  echo "Runtime tools ready: EPUBCheck 5.4.0; bundled libmobi unavailable"
fi
