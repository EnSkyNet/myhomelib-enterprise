#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./mvnw -pl myhomelib-bootstrap -am clean package -DskipTests "$@"
echo "Executable Spring Boot JAR: myhomelib-bootstrap/target/myhomelib-bootstrap-7.1.0.jar"
