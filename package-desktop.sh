#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
TYPE=${1:-app-image}
shift || true
./mvnw -pl myhomelib-bootstrap -am package -DskipTests -Pproduction
JAR="myhomelib-bootstrap/target/myhomelib-bootstrap-1.0.0.jar"
[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }
STAGE="myhomelib-bootstrap/target/jpackage-input"
DEST="dist"
rm -rf "$STAGE"
mkdir -p "$STAGE" "$DEST"
cp "$JAR" "$STAGE/"
rm -rf "$DEST/MyHomeLib"
jpackage --type "$TYPE" --name MyHomeLib --app-version 1.0.0 \
  --input "$STAGE" --main-jar "$(basename "$JAR")" \
  --dest "$DEST" --java-options "-Dfile.encoding=UTF-8" "$@"
echo "Desktop package created under: $DEST"
