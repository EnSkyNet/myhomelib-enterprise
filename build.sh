#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Daily edit/build cycle: only the desktop dependency graph, no test execution.
# Keep ./build.sh (without --fast) as the full CI-equivalent safety gate.
if [[ "${1:-}" == "--fast" ]]; then
  shift
  exec bash ./tools/invoke-maven.sh -pl myhomelib-bootstrap -am package -DskipTests "$@"
fi
exec bash ./tools/invoke-maven.sh clean verify "$@"
