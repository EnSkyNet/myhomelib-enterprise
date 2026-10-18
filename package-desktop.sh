#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TYPE=${1:-app-image}
shift || true
RUNTIME_MODULES="java.base,java.desktop,java.instrument,java.management,java.net.http,jdk.management,java.prefs,java.scripting,java.sql.rowset,jdk.compiler,jdk.httpserver,jdk.incubator.vector,jdk.jfr,jdk.unsupported,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.zipfs"
VERSION=${MHL_VERSION:-$(python3 - <<'PY'
import xml.etree.ElementTree as ET
root=ET.parse('pom.xml').getroot()
ns={'m':'http://maven.apache.org/POM/4.0.0'}
print(root.findtext('m:version', namespaces=ns) or '')
PY
)}
[[ -n "$VERSION" ]] || { echo "Cannot determine version from pom.xml" >&2; exit 2; }

if [[ "${MHL_SKIP_BUILD:-0}" != "1" ]]; then
  bash ./tools/invoke-maven.sh -pl myhomelib-bootstrap,myhomelib-updater -am package -DskipTests -Pproduction
fi
bash ./tools/prepare-runtime-tools.sh
JAR="myhomelib-bootstrap/target/myhomelib-bootstrap-${VERSION}.jar"
UPDATER_JAR="myhomelib-updater/target/myhomelib-updater-${VERSION}.jar"
[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }
[ -f "$UPDATER_JAR" ] || { echo "Missing $UPDATER_JAR" >&2; exit 1; }
STAGE="myhomelib-bootstrap/target/jpackage-input"
CONTENT="myhomelib-bootstrap/target/jpackage-content"
APP_CONTENT="$CONTENT/app"
DEST="dist"
rm -rf "$STAGE" "$CONTENT"
mkdir -p "$STAGE" "$APP_CONTENT/tools/validators" "$APP_CONTENT/tools/updater" "$DEST"
cp "$JAR" "$STAGE/"
cp "$UPDATER_JAR" "$APP_CONTENT/tools/updater/"
cp -R "tools/validators/epubcheck" "$APP_CONTENT/tools/validators/"
if [[ -x "tools/converters/libmobi/mobitool" ]]; then
  LIBMOBI_LICENSE="tools/converters/libmobi/COPYING"
  [[ -f "$LIBMOBI_LICENSE" ]] || LIBMOBI_LICENSE="tools/converters/libmobi/build-src/COPYING"
  [[ -f "$LIBMOBI_LICENSE" ]] || {
    echo "ERROR: refusing to package mobitool without its bundled license file" >&2
    exit 2
  }
  mkdir -p "$APP_CONTENT/tools/converters/libmobi"
  cp "tools/converters/libmobi/mobitool" "$APP_CONTENT/tools/converters/libmobi/mobitool"
  cp "$LIBMOBI_LICENSE" "$APP_CONTENT/tools/converters/libmobi/COPYING"
  chmod 0755 "$APP_CONTENT/tools/converters/libmobi/mobitool"
fi
if [[ "$TYPE" == "app-image" ]]; then
  rm -rf "$DEST/MyHomeLib" "$DEST/MyHomeLib.app"
fi
jpackage --type "$TYPE" --name MyHomeLib --app-version "$VERSION" \
  --vendor "MyHomeLib Corp" --description "MyHomeLib Enterprise library manager" \
  --input "$STAGE" --main-jar "$(basename "$JAR")" \
  --app-content "$APP_CONTENT" --dest "$DEST" --java-options "-Dfile.encoding=UTF-8" \
  --java-options "-Dspring.profiles.active=prod" \
  --java-options "-Xms32m" --java-options "-Xmx1024m" \
  --java-options "-XX:MinHeapFreeRatio=10" --java-options "-XX:MaxHeapFreeRatio=20" \
  --add-modules "$RUNTIME_MODULES" \
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
