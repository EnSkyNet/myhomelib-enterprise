#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./mvnw clean verify -Pproduction
./package-desktop.sh app-image
mkdir -p dist
cp -f myhomelib-mcp/target/myhomelib-mcp-1.0.0.jar dist/
cp -f myhomelib-bootstrap/target/myhomelib-bootstrap-1.0.0.jar dist/
./checksums.sh dist
echo "Release candidate artifacts are in dist/. Run the clean-machine checklist before signing a FINAL release."
