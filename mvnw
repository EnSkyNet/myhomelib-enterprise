#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
EMBEDDED_MAVEN="$BASE_DIR/.mvn/maven/apache-maven-3.9.6/bin/mvn"
WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"

if [ -f "$EMBEDDED_MAVEN" ]; then
  exec sh "$EMBEDDED_MAVEN" "$@"
fi

if [ -f "$WRAPPER_JAR" ]; then
  exec java -classpath "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
fi

if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

echo "ERROR: Maven is unavailable. Keep .mvn/wrapper/maven-wrapper.jar or install Maven 3.9.6+." >&2
exit 127
