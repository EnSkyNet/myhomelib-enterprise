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
  ./mvnw -pl myhomelib-bootstrap -am package -DskipTests -Pproduction
fi
JAR="myhomelib-bootstrap/target/myhomelib-bootstrap-${VERSION}.jar"
[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }
STAGE="myhomelib-bootstrap/target/jpackage-input"
DEST="dist"
rm -rf "$STAGE"
mkdir -p "$STAGE" "$DEST"
cp "$JAR" "$STAGE/"
if [[ "$TYPE" == "app-image" ]]; then
  rm -rf "$DEST/MyHomeLib" "$DEST/MyHomeLib.app"
fi
jpackage --type "$TYPE" --name MyHomeLib --app-version "$VERSION" \
  --vendor "MyHomeLib Corp" --description "MyHomeLib Enterprise library manager" \
  --input "$STAGE" --main-jar "$(basename "$JAR")" \
  --dest "$DEST" --java-options "-Dfile.encoding=UTF-8" "$@"
echo "Desktop package created under: $DEST (version $VERSION, type $TYPE)"
