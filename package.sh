#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./tools/invoke-maven.sh -pl myhomelib-bootstrap -am clean package -DskipTests "$@"
echo "Executable Spring Boot JAR: myhomelib-bootstrap/target/myhomelib-bootstrap-8.0.0.jar"
