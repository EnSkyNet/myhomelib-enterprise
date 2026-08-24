#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./mvnw -pl myhomelib-bootstrap -am install -DskipTests
exec ./mvnw -f myhomelib-bootstrap/pom.xml javafx:run "$@"
