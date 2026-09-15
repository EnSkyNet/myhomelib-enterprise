#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./tools/invoke-maven.sh -pl myhomelib-mcp -am package "$@"
echo "MCP shaded JAR: myhomelib-mcp/target/myhomelib-mcp-8.0.0.jar"
