#!/usr/bin/env sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
[ -f "$PROPS" ] || { echo "Missing $PROPS" >&2; exit 1; }

DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPS" | tail -n 1)
[ -n "$DIST_URL" ] || { echo "distributionUrl is not configured" >&2; exit 1; }

MVN_VERSION=$(printf '%s' "$DIST_URL" | sed -n 's#.*apache-maven/\([^/]*\)/apache-maven-.*#\1#p')
[ -n "$MVN_VERSION" ] || { echo "Cannot determine Maven version from distributionUrl" >&2; exit 1; }

WRAPPER_HOME=${MAVEN_WRAPPER_HOME:-"${HOME:-$BASE_DIR}/.m2/wrapper/dists"}
DIST_DIR="$WRAPPER_HOME/apache-maven-$MVN_VERSION"
MVN_BIN="$DIST_DIR/apache-maven-$MVN_VERSION/bin/mvn"

if [ ! -x "$MVN_BIN" ]; then
  mkdir -p "$DIST_DIR"
  TMP_ZIP="$DIST_DIR/apache-maven-$MVN_VERSION-bin.zip.part"
  rm -f "$TMP_ZIP"
  echo "Downloading Maven $MVN_VERSION..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 20 "$DIST_URL" -o "$TMP_ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP_ZIP" "$DIST_URL"
  else
    echo "curl or wget is required for the first wrapper run" >&2
    exit 1
  fi
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required for the first wrapper run" >&2; exit 1; }
  rm -rf "$DIST_DIR/apache-maven-$MVN_VERSION"
  unzip -q "$TMP_ZIP" -d "$DIST_DIR"
  rm -f "$TMP_ZIP"
fi

exec "$MVN_BIN" "$@"
