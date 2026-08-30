#!/bin/sh
# Maven Wrapper launcher for Unix-like systems.
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "ERROR: Maven wrapper jar not found: $WRAPPER_JAR" >&2
  exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=$(command -v java || true)
fi
if [ -z "${JAVA:-}" ]; then
  echo "ERROR: Java is required to run Maven Wrapper" >&2
  exit 1
fi

exec "$JAVA" \
  -Dmaven.multiModuleProjectDirectory="$BASE_DIR" \
  ${MAVEN_OPTS:-} \
  -classpath "$WRAPPER_JAR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
