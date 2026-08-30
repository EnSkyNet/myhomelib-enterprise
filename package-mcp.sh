#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./mvnw -pl myhomelib-mcp -am package "$@"
echo "MCP shaded JAR: myhomelib-mcp/target/myhomelib-mcp-7.1.0.jar"
