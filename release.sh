#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
VERSION=${MHL_VERSION:-$(python3 - <<'PY'
import xml.etree.ElementTree as ET
root=ET.parse('pom.xml').getroot(); ns={'m':'http://maven.apache.org/POM/4.0.0'}
print(root.findtext('m:version', namespaces=ns) or '')
PY
)}
[[ -n "$VERSION" ]] || { echo "Cannot determine version from pom.xml" >&2; exit 2; }
./mvnw clean verify -Pproduction
export MHL_SKIP_BUILD=1
./package-portable.sh
./smoke-desktop.sh
./smoke-portable.sh
# The app-image is an intermediate packaging directory. The versioned portable
# archive already contains it, so remove the raw image before checksums/publication.
rm -rf dist/MyHomeLib dist/MyHomeLib.app
mkdir -p dist
cp -f "myhomelib-mcp/target/myhomelib-mcp-${VERSION}.jar" dist/
cp -f "myhomelib-bootstrap/target/myhomelib-bootstrap-${VERSION}.jar" dist/
./checksums.sh dist
python3 tools/stage23-cross-platform-release-check.py --dist dist --require-checksums --require-portable
echo "Release candidate artifacts are in dist/ and passed artifact validation."
