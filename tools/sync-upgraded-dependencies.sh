#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
echo "Synchronizing Maven dependencies for Spring Boot 4.1.1 / JavaFX 21.0.12 / SQLite JDBC 3.53.4.0..."
./mvnw -B -ntp -U dependency:go-offline
./mvnw -B -ntp -U test-compile -DskipTests
echo "Dependency cache prepared at $ROOT/.mvn/repository"
echo "Now run: ./tools/verify-upgraded-offline.sh"
