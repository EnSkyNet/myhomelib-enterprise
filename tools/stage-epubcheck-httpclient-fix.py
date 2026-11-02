#!/usr/bin/env python3
"""Stage Apache HttpClient 5.6.5 with Apache release SHA-512 and POM verification.

The staged files are not applied automatically. Use apply-epubcheck-httpclient-fix.py
only after the stage receipt has been reviewed.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

GROUP = "org.apache.httpcomponents.client5"
ARTIFACT = "httpclient5"
VERSION = "5.6.5"
CORE_VERSION = "5.4.4"
REPOSITORY = "https://repo.maven.apache.org/maven2"
APACHE_RELEASES = "https://downloads.apache.org/httpcomponents/httpclient/binary"
MAX_DOWNLOAD = 32 * 1024 * 1024
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def download(url: str, limit: int = MAX_DOWNLOAD) -> bytes:
    request = urllib.request.Request(
        url, headers={"User-Agent": "MyHomeLib-Enterprise-v34-vendor-verifier/1.0"}
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"Download exceeds safety limit: {url}")
    if not data:
        raise ValueError(f"Empty download: {url}")
    return data


def parse_sha512(checksum_data: bytes) -> str:
    text = checksum_data.decode("ascii", errors="strict").strip()
    match = re.search(r"(?i)(?<![0-9a-f])[0-9a-f]{128}(?![0-9a-f])", text)
    if not match:
        raise ValueError("Maven Central .sha512 file does not contain one SHA-512 digest")
    return match.group(0).lower()


def verify_apache_release_jar(maven_jar_url: str) -> tuple[bytes, str]:
    """Verify the Maven JAR byte-for-byte against Apache's SHA-512 checked release.

    Maven Central does not guarantee .sha512 sidecars for each JAR or POM. Never
    accept a missing sidecar as a reason to use a weaker hash without independent
    evidence. Instead, verify Apache's official binary distribution and require
    the exact same JAR bytes as the Maven Central publication.
    """
    release_name = f"httpcomponents-client-{VERSION}-bin.zip"
    release_url = f"{APACHE_RELEASES}/{release_name}"
    expected = parse_sha512(download(release_url + ".sha512", 4096))
    binary_release = download(release_url)
    actual = hashlib.sha512(binary_release).hexdigest()
    if actual != expected:
        raise ValueError("Apache release ZIP SHA-512 mismatch")
    with zipfile.ZipFile(io.BytesIO(binary_release)) as release:
        matches = [entry for entry in release.infolist()
                   if entry.filename.endswith(f"/httpclient5-{VERSION}.jar")
                   and not entry.is_dir()]
        if len(matches) != 1:
            raise ValueError("Apache binary release must contain exactly one HttpClient JAR")
        if matches[0].file_size > MAX_DOWNLOAD:
            raise ValueError("Apache binary release contains oversized HttpClient JAR")
        release_jar = release.read(matches[0])
    verify_jar_identity(release_jar)
    maven_jar = download(maven_jar_url)
    if hashlib.sha256(maven_jar).digest() != hashlib.sha256(release_jar).digest():
        raise ValueError("Maven Central HttpClient JAR differs from verified Apache release")
    return maven_jar, hashlib.sha512(maven_jar).hexdigest()


def verify_module_pom(data: bytes, jar_data: bytes) -> None:
    """Require the repository POM to match the Maven metadata inside the verified JAR."""
    with zipfile.ZipFile(io.BytesIO(jar_data)) as archive:
        embedded = archive.read(f"META-INF/maven/{GROUP}/{ARTIFACT}/pom.xml")
    if ET.canonicalize(data.decode("utf-8")) != ET.canonicalize(embedded.decode("utf-8")):
        raise ValueError("Maven Central module POM does not match verified JAR metadata")


def read_properties(data: bytes) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in data.decode("utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def verify_jar_identity(data: bytes) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        properties_path = f"META-INF/maven/{GROUP}/{ARTIFACT}/pom.properties"
        pom_path = f"META-INF/maven/{GROUP}/{ARTIFACT}/pom.xml"
        properties = read_properties(archive.read(properties_path))
        if properties.get("groupId") != GROUP:
            raise ValueError("Unexpected embedded HttpClient groupId")
        if properties.get("artifactId") != ARTIFACT:
            raise ValueError("Unexpected embedded HttpClient artifactId")
        if properties.get("version") != VERSION:
            raise ValueError("Unexpected embedded HttpClient version")
        pom = ET.fromstring(archive.read(pom_path))
        parent = pom.find("m:parent", NS)
        if parent is None:
            raise ValueError("HttpClient module POM has no parent")
        parent_group = parent.findtext("m:groupId", namespaces=NS)
        parent_artifact = parent.findtext("m:artifactId", namespaces=NS)
        parent_version = parent.findtext("m:version", namespaces=NS)
        if (parent_group, parent_artifact, parent_version) != (
            GROUP, "httpclient5-parent", VERSION
        ):
            raise ValueError("Unexpected HttpClient parent coordinates")


def verify_parent_pom(data: bytes) -> None:
    pom = ET.fromstring(data)
    group = pom.findtext("m:groupId", namespaces=NS)
    artifact = pom.findtext("m:artifactId", namespaces=NS)
    version = pom.findtext("m:version", namespaces=NS)
    core = pom.findtext("m:properties/m:httpcore.version", namespaces=NS)
    if (group, artifact, version) != (GROUP, "httpclient5-parent", VERSION):
        raise ValueError("Unexpected HttpClient parent POM coordinates")
    if core != CORE_VERSION:
        raise ValueError(
            f"HttpClient {VERSION} requires unexpected HttpCore version: {core!r}"
        )


def stage(output: Path) -> dict[str, object]:
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("staging directory must be new or empty")
    output.mkdir(parents=True, exist_ok=True)

    base = f"{REPOSITORY}/{GROUP.replace('.', '/')}"
    jar_name = f"{ARTIFACT}-{VERSION}.jar"
    pom_name = f"{ARTIFACT}-{VERSION}.pom"
    parent_name = f"httpclient5-parent-{VERSION}.pom"
    jar_url = f"{base}/{ARTIFACT}/{VERSION}/{jar_name}"
    pom_url = f"{base}/{ARTIFACT}/{VERSION}/{pom_name}"
    parent_url = f"{base}/httpclient5-parent/{VERSION}/{parent_name}"

    jar_data, jar_sha512 = verify_apache_release_jar(jar_url)
    pom_data = download(pom_url)
    verify_module_pom(pom_data, jar_data)
    pom_sha512 = hashlib.sha512(pom_data).hexdigest()
    # Maven parent POM is downloaded over authenticated HTTPS; its coordinates
    # and the compatible bundled HttpCore version are checked below.
    parent_data = download(parent_url)
    parent_sha512 = hashlib.sha512(parent_data).hexdigest()
    verify_parent_pom(parent_data)

    (output / jar_name).write_bytes(jar_data)
    (output / f"{jar_name}.sha512").write_text(jar_sha512 + "\n", encoding="ascii")
    (output / pom_name).write_bytes(pom_data)
    (output / f"{pom_name}.sha512").write_text(pom_sha512 + "\n", encoding="ascii")
    (output / parent_name).write_bytes(parent_data)
    (output / f"{parent_name}.sha512").write_text(parent_sha512 + "\n", encoding="ascii")

    receipt = {
        "schemaVersion": 1,
        "groupId": GROUP,
        "artifactId": ARTIFACT,
        "version": VERSION,
        "httpcoreVersion": CORE_VERSION,
        "sourceRepository": REPOSITORY,
        "verification": "Apache binary release SHA-512 and exact Maven Central JAR match; canonical embedded module POM match",
        "apacheBinaryRelease": f"{APACHE_RELEASES}/httpcomponents-client-{VERSION}-bin.zip",
        "jar": {
            "file": jar_name,
            "sizeBytes": len(jar_data),
            "sha256": hashlib.sha256(jar_data).hexdigest(),
            "sha512": jar_sha512,
            "url": jar_url,
        },
        "pom": {"file": pom_name, "sha512": pom_sha512, "url": pom_url},
        "parentPom": {
            "file": parent_name,
            "sha512": parent_sha512,
            "url": parent_url,
            "httpcoreVersion": CORE_VERSION,
        },
    }
    (output / "receipt.json").write_text(
        json.dumps(receipt, indent=2) + "\n", encoding="utf-8"
    )
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    receipt = stage(args.output)
    print(
        "STAGED VERIFIED: "
        f"{receipt['groupId']}:{receipt['artifactId']}:{receipt['version']} "
        f"(HttpCore {receipt['httpcoreVersion']})"
    )
    print(f"SHA-256: {receipt['jar']['sha256']}")
    print(f"SHA-512: {receipt['jar']['sha512']}")
    print("No tracked vendor files were modified.")


if __name__ == "__main__":
    main()
