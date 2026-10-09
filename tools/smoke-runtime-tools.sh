#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="$(uname -s)"

case "$PLATFORM" in
  Linux)
    IMAGE="$ROOT/dist/MyHomeLib"
    JAVA="$IMAGE/lib/runtime/bin/java"
    EPUBCHECK="$IMAGE/lib/app/tools/validators/epubcheck/epubcheck.jar"
    MOBITOOL="$IMAGE/lib/app/tools/converters/libmobi/mobitool"
    ;;
  Darwin)
    IMAGE="$ROOT/dist/MyHomeLib.app"
    JAVA="$IMAGE/Contents/runtime/Contents/Home/bin/java"
    EPUBCHECK="$IMAGE/Contents/app/tools/validators/epubcheck/epubcheck.jar"
    MOBITOOL="$IMAGE/Contents/app/tools/converters/libmobi/mobitool"
    ;;
  *)
    echo "ERROR: unsupported platform for runtime-tools smoke: $PLATFORM" >&2
    exit 2
    ;;
esac

[[ -x "$JAVA" ]] || { echo "ERROR: packaged Java launcher missing: $JAVA" >&2; exit 1; }
[[ -s "$EPUBCHECK" ]] || { echo "ERROR: packaged EPUBCheck JAR missing: $EPUBCHECK" >&2; exit 1; }
REQUIRE_LIBMOBI="${MHL_REQUIRE_LIBMOBI:-0}"

EPUB_VERSION="$("$JAVA" -jar "$EPUBCHECK" --version 2>&1)"
grep -F "EPUBCheck v5.4.0" <<<"$EPUB_VERSION" >/dev/null || {
  echo "ERROR: unexpected packaged EPUBCheck version: $EPUB_VERSION" >&2
  exit 1
}

MOBI_VERSION=""
if [[ -x "$MOBITOOL" ]]; then
  MOBI_VERSION="$("$MOBITOOL" -v 2>&1)"
  grep -F "libmobi: 0.12" <<<"$MOBI_VERSION" >/dev/null || {
    echo "ERROR: unexpected packaged libmobi version: $MOBI_VERSION" >&2
    exit 1
  }
elif [[ "$REQUIRE_LIBMOBI" == "1" ]]; then
  echo "ERROR: packaged mobitool missing/not executable: $MOBITOOL" >&2
  exit 1
else
  echo "WARNING: packaged mobitool is absent; optional MOBI/AZW conversion tool was not bundled." >&2
fi

echo "Packaged runtime tools smoke: PASS"
echo "$EPUB_VERSION"
[[ -z "$MOBI_VERSION" ]] || printf '%s\n' "$MOBI_VERSION"
