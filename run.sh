#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./tools/invoke-maven.sh -pl myhomelib-bootstrap -am install -DskipTests
exec ./tools/invoke-maven.sh -f myhomelib-bootstrap/pom.xml javafx:run "$@"
