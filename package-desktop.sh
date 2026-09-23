#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TYPE=${1:-app-image}
shift || true
VERSION=${MHL_VERSION:-$(python3 - <<'PY'
import xml.etree.ElementTree as ET
root=ET.parse('pom.xml').getroot()
ns={'m':'http://maven.apache.org/POM/4.0.0'}
print(root.findtext('m:version', namespaces=ns) or '')
PY
)}
[[ -n "$VERSION" ]] || { echo "Cannot determine version from pom.xml" >&2; exit 2; }

if [[ "${MHL_SKIP_BUILD:-0}" != "1" ]]; then
  ./tools/invoke-maven.sh -pl myhomelib-bootstrap -am package -DskipTests -Pproduction
fi
./tools/prepare-runtime-tools.sh
JAR="myhomelib-bootstrap/target/myhomelib-bootstrap-${VERSION}.jar"
[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }
STAGE="myhomelib-bootstrap/target/jpackage-input"
DEST="dist"
rm -rf "$STAGE"
mkdir -p "$STAGE" "$DEST"
cp "$JAR" "$STAGE/"
mkdir -p "$STAGE/tools/validators" "$STAGE/tools/converters/libmobi"
cp -R "tools/validators/epubcheck" "$STAGE/tools/validators/"
cp "tools/converters/libmobi/mobitool" "$STAGE/tools/converters/libmobi/mobitool"
cp "tools/converters/libmobi/build-src/COPYING" "$STAGE/tools/converters/libmobi/COPYING"
chmod 0755 "$STAGE/tools/converters/libmobi/mobitool"
if [[ "$TYPE" == "app-image" ]]; then
  rm -rf "$DEST/MyHomeLib" "$DEST/MyHomeLib.app"
fi
jpackage --type "$TYPE" --name MyHomeLib --app-version "$VERSION" \
  --vendor "MyHomeLib Corp" --description "MyHomeLib Enterprise library manager" \
  --input "$STAGE" --main-jar "$(basename "$JAR")" \
  --dest "$DEST" --java-options "-Dfile.encoding=UTF-8" \
  --jlink-options "--strip-debug --no-man-pages --no-header-files" "$@"

# On Linux, Java 21 uses UTF-8 for Charset.defaultCharset() but UnixPath still
# follows the native/JNU charset selected before the JVM starts. A launcher
# started from LANG=C can therefore fail on Cyrillic filesystem paths even with
# -Dfile.encoding=UTF-8. Keep the jpackage launcher intact as MyHomeLib.real and
# put a tiny shell launcher in front of it so a UTF-8 locale is selected before
# the JVM is created.
if [[ "$TYPE" == "app-image" && "$(uname -s)" == "Linux" ]]; then
  IMAGE="$DEST/MyHomeLib"
  LAUNCHER="$IMAGE/bin/MyHomeLib"
  REAL_LAUNCHER="$IMAGE/bin/MyHomeLib.real"
  CFG="$IMAGE/lib/app/MyHomeLib.cfg"
  REAL_CFG="$IMAGE/lib/app/MyHomeLib.real.cfg"

  [[ -x "$LAUNCHER" && -f "$CFG" ]] || {
    echo "Incomplete Linux jpackage image: launcher/config not found" >&2
    exit 1
  }

  mv "$LAUNCHER" "$REAL_LAUNCHER"
  mv "$CFG" "$REAL_CFG"
  cat > "$LAUNCHER" <<'EOF'
#!/bin/sh
set -eu

SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REAL_LAUNCHER="$SELF_DIR/MyHomeLib.real"

is_utf8_charmap() {
  case "${1:-}" in
    UTF-8|utf8|UTF8|utf-8) return 0 ;;
    *) return 1 ;;
  esac
}

current_charmap=$(locale charmap 2>/dev/null || true)
if ! is_utf8_charmap "$current_charmap"; then
  found_locale=''
  for candidate in C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8 uk_UA.UTF-8 uk_UA.utf8; do
    candidate_charmap=$(LANG="$candidate" LC_ALL="$candidate" locale charmap 2>/dev/null || true)
    if is_utf8_charmap "$candidate_charmap"; then
      found_locale=$candidate
      break
    fi
  done
  if [ -z "$found_locale" ]; then
    echo "MYHOMELIB_ENCODING_ERROR: no usable UTF-8 locale was found; non-ASCII filesystem paths are unsafe." >&2
    exit 78
  fi
  export LANG="$found_locale"
  export LC_ALL="$found_locale"
fi

exec "$REAL_LAUNCHER" "$@"
EOF
  chmod 0755 "$LAUNCHER"
fi

echo "Desktop package created under: $DEST (version $VERSION, type $TYPE)"
