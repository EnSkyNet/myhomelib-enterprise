#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash ./tools/invoke-maven.sh -pl myhomelib-bootstrap -am install -DskipTests
exec bash ./tools/invoke-maven.sh -f myhomelib-bootstrap/pom.xml javafx:run "$@"
