#!/usr/bin/env python3
"""Verify the vendored EPUBCheck HttpClient fix and exercise its classic HTTP path."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "tools/validators/epubcheck"
LIB = VENDOR / "lib"
EXPECTED_CLIENT = "httpclient5-5.6.5.jar"
EXPECTED_CORE = "httpcore5-5.4.4.jar"
EXPECTED_H2 = "httpcore5-h2-5.4.4.jar"


def fail(message: str) -> None:
    raise SystemExit("EPUBCHECK HTTPCLIENT SECURITY CHECK FAILED: " + message)


def properties(jar: Path, path: str) -> dict[str, str]:
    import io
    with zipfile.ZipFile(jar) as archive:
        raw = archive.read(path).decode("utf-8")
    result = {}
    for line in raw.splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def main() -> None:
    client = LIB / EXPECTED_CLIENT
    core = LIB / EXPECTED_CORE
    h2 = LIB / EXPECTED_H2
    for path in (client, core, h2, VENDOR / "epubcheck.jar"):
        if not path.is_file():
            fail(f"required runtime file missing: {path.relative_to(ROOT)}")
    if (LIB / "httpclient5-5.1.3.jar").exists():
        fail("vulnerable HttpClient 5.1.3 JAR is still present")
    if properties(client, "META-INF/maven/org.apache.httpcomponents.client5/httpclient5/pom.properties").get("version") != "5.6.5":
        fail("HttpClient embedded Maven version is not 5.6.5")
    if properties(core, "META-INF/maven/org.apache.httpcomponents.core5/httpcore5/pom.properties").get("version") != "5.4.4":
        fail("HttpCore embedded Maven version is not 5.4.4")
    if properties(h2, "META-INF/maven/org.apache.httpcomponents.core5/httpcore5-h2/pom.properties").get("version") != "5.4.4":
        fail("HttpCore H2 embedded Maven version is not 5.4.4")

    epub_jar = VENDOR / "epubcheck.jar"
    with zipfile.ZipFile(epub_jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    logical = []
    for line in manifest.replace("\r\n", "\n").splitlines():
        if line.startswith(" ") and logical:
            logical[-1] += line[1:]
        else:
            logical.append(line)
    class_path_lines = [line for line in logical if line.startswith("Class-Path: ")]
    if len(class_path_lines) != 1:
        fail("EPUBCheck manifest must contain exactly one Class-Path")
    tokens = class_path_lines[0][len("Class-Path: "):].split()
    for expected in (f"lib/{EXPECTED_CLIENT}", f"lib/{EXPECTED_CORE}", f"lib/{EXPECTED_H2}"):
        if expected not in tokens:
            fail(f"EPUBCheck manifest does not reference {expected}")
    if any("5.1.3" in token for token in tokens):
        fail("stale 5.1.3 manifest dependency remains")
    missing = [token for token in tokens if not (VENDOR / token).is_file()]
    if missing:
        fail(f"manifest references missing runtime libraries: {missing}")

    xmlresolver = LIB / "xmlresolver-4.4.3.jar"
    with zipfile.ZipFile(xmlresolver) as archive:
        resource_connection = archive.read("org/xmlresolver/ResourceConnection.class")
    if b"org/apache/hc/client5/http/impl/classic/HttpClients" not in resource_connection:
        fail("XMLResolver remote-resource path no longer identifies classic HttpClient usage")

    manifest_data = json.loads((ROOT / "security/binary-manifest.json").read_text(encoding="utf-8"))
    components = {item["path"]: item for item in manifest_data["components"]}
    client_path = f"tools/validators/epubcheck/lib/{EXPECTED_CLIENT}"
    for path in (client_path, f"tools/validators/epubcheck/lib/{EXPECTED_CORE}",
                 f"tools/validators/epubcheck/lib/{EXPECTED_H2}", "tools/validators/epubcheck/epubcheck.jar"):
        component = components.get(path)
        actual = ROOT / path
        if component is None or component["sha256"] != hashlib.sha256(actual.read_bytes()).hexdigest():
            fail(f"binary manifest SHA-256 mismatch: {path}")
        if component["sizeBytes"] != actual.stat().st_size:
            fail(f"binary manifest size mismatch: {path}")
    if components[client_path]["version"] != "5.6.5":
        fail("binary manifest does not record HttpClient 5.6.5")
    if components[client_path]["purl"] != "pkg:maven/org.apache.httpcomponents.client5/httpclient5@5.6.5":
        fail("binary manifest HttpClient PURL is incorrect")

    sha_file = ROOT / "tools/CONVERTERS-SHA256.txt"
    sha_entries = {}
    for line in sha_file.read_text(encoding="utf-8").splitlines():
        if line.strip():
            digest, path = line.split("  ", 1)
            sha_entries[path] = digest
    if sha_entries.get(client_path) != hashlib.sha256(client.read_bytes()).hexdigest():
        fail("CONVERTERS-SHA256.txt does not match HttpClient 5.6.5")
    third_party = (VENDOR / "THIRD-PARTY.txt").read_text(encoding="utf-8")
    if "httpclient5-5.6.5.jar" not in third_party and "HttpClient 5.6.5" not in third_party:
        fail("THIRD-PARTY.txt does not record HttpClient 5.6.5")

    java = shutil.which("java")
    javac = shutil.which("javac")
    if not java or not javac:
        fail("JDK is required for the HTTP regression probe")
    source = ROOT / "tools/tests/epubcheck/HttpClientPoolLeakProbe.java"
    class_path = os.pathsep.join((str(epub_jar), str(LIB / "*")))
    with tempfile.TemporaryDirectory(prefix="mhl-httpclient-probe-") as tmp:
        subprocess.run([javac, "-cp", class_path, "-d", tmp, str(source)],
                       cwd=ROOT, check=True, timeout=60)
        subprocess.run([java, "-cp", os.pathsep.join((tmp, class_path)),
                        "HttpClientPoolLeakProbe"], cwd=ROOT, check=True, timeout=45)
    version = subprocess.run([java, "-jar", str(epub_jar), "--version"],
                             cwd=ROOT, text=True, capture_output=True, check=True, timeout=30)
    output = version.stdout + version.stderr
    if "EPUBCheck v5.4.0" not in output:
        fail("EPUBCheck version smoke did not identify 5.4.0")
    subprocess.run([java, "-jar", str(epub_jar), "--help"],
                   cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   check=True, timeout=30)
    print("EPUBCHECK HTTPCLIENT SECURITY CHECK PASS: 5.6.5 / HttpCore 5.4.4; "
          "manifest, metadata, hashes, XMLResolver and invalid Content-Encoding pool recovery verified.")


if __name__ == "__main__":
    main()
