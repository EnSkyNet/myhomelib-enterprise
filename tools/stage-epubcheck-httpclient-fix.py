#!/usr/bin/env python3
"""Stage Apache HttpClient 5.6.5 from Maven Central with SHA-512 and POM verification.

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


def verify_download(url: str) -> tuple[bytes, str]:
    checksum_data = download(url + ".sha512", 4096)
    expected = parse_sha512(checksum_data)
    data = download(url)
    actual = hashlib.sha512(data).hexdigest()
    if actual != expected:
        raise ValueError(f"Published SHA-512 mismatch: {url}")
    return data, actual


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

    jar_data, jar_sha512 = verify_download(jar_url)
    verify_jar_identity(jar_data)
    pom_data, pom_sha512 = verify_download(pom_url)
    parent_data, parent_sha512 = verify_download(parent_url)
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
