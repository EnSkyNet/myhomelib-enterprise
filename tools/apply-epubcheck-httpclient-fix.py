#!/usr/bin/env python3
"""Apply the verified Apache HttpClient 5.6.5 vendor replacement to EPUBCheck."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import io
import json
from datetime import date
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STAGE_SCRIPT = Path(__file__).with_name("stage-epubcheck-httpclient-fix.py")
spec = importlib.util.spec_from_file_location("stage_epubcheck_httpclient", STAGE_SCRIPT)
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)

OLD_VERSION = "5.1.3"
NEW_VERSION = "5.6.5"
CORE_VERSION = "5.4.4"
OLD_PATH = f"tools/validators/epubcheck/lib/httpclient5-{OLD_VERSION}.jar"
NEW_PATH = f"tools/validators/epubcheck/lib/httpclient5-{NEW_VERSION}.jar"
EPC_PATH = "tools/validators/epubcheck/epubcheck.jar"
MANIFEST_PATH = "META-INF/MANIFEST.MF"
CLASS_PATH_REPLACEMENTS = {
    f"lib/httpclient5-{OLD_VERSION}.jar": f"lib/httpclient5-{NEW_VERSION}.jar",
    f"lib/httpcore5-{OLD_VERSION}.jar": f"lib/httpcore5-{CORE_VERSION}.jar",
    f"lib/httpcore5-h2-{OLD_VERSION}.jar": f"lib/httpcore5-h2-{CORE_VERSION}.jar",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def unfold_manifest(data: bytes) -> list[str]:
    physical = data.decode("utf-8").replace("\r\n", "\n").split("\n")
    logical: list[str] = []
    for line in physical:
        if line.startswith(" "):
            if not logical:
                raise ValueError("Invalid JAR manifest continuation line")
            logical[-1] += line[1:]
        elif line:
            logical.append(line)
    return logical


def wrap_manifest(lines: list[str]) -> bytes:
    physical: list[str] = []
    for line in lines:
        if not line.startswith("Class-Path: "):
            physical.append(line)
            continue
        prefix = "Class-Path: "
        current = prefix
        for token in line[len(prefix):].split():
            candidate = current + ("" if current == prefix else " ") + token
            if len(candidate.encode("utf-8")) > 70 and current != prefix:
                physical.append(current)
                current = " " + token
            else:
                current = candidate
        physical.append(current)
    return ("\r\n".join(physical) + "\r\n").encode("utf-8")


def update_manifest(data: bytes, lib_dir: Path, future_files: set[str] | None = None) -> bytes:
    future_files = future_files or set()
    lines = unfold_manifest(data)
    indexes = [i for i, line in enumerate(lines) if line.startswith("Class-Path: ")]
    if len(indexes) != 1:
        raise ValueError(f"Expected one Class-Path manifest attribute, found {len(indexes)}")
    index = indexes[0]
    tokens = lines[index][len("Class-Path: "):].split()
    for old, new in CLASS_PATH_REPLACEMENTS.items():
        if old not in tokens:
            raise ValueError(f"Expected stale manifest Class-Path entry is absent: {old}")
        tokens[tokens.index(old)] = new
    if len(tokens) != len(set(tokens)):
        raise ValueError("Duplicate manifest Class-Path entries")
    for token in tokens:
        if token.startswith(("http:", "https:", "file:")) or ".." in Path(token).parts:
            raise ValueError(f"Unsafe EPUBCheck manifest Class-Path entry: {token}")
        target = (lib_dir.parent / token).resolve()
        if (not target.is_file() and token not in future_files) or lib_dir.parent.resolve() not in target.parents:
            raise ValueError(f"Manifest Class-Path entry is missing or escapes vendor folder: {token}")
    lines[index] = "Class-Path: " + " ".join(tokens)
    return wrap_manifest(lines)


def rewrite_epubcheck_jar(jar_path: Path, lib_dir: Path) -> bytes:
    source = jar_path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        names = archive.namelist()
        if MANIFEST_PATH not in names:
            raise ValueError("EPUBCheck JAR has no META-INF/MANIFEST.MF")
        signatures = [
            name for name in names
            if name.upper().startswith("META-INF/")
            and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))
        ]
        if signatures:
            raise ValueError(f"Refusing to rewrite signed EPUBCheck JAR: {signatures}")
        new_manifest = update_manifest(
            archive.read(MANIFEST_PATH), lib_dir,
            future_files={f"lib/httpclient5-{NEW_VERSION}.jar"},
        )
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as updated:
            for info in archive.infolist():
                content = new_manifest if info.filename == MANIFEST_PATH else archive.read(info.filename)
                updated.writestr(info, content)
    return output.getvalue()


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def apply(root: Path, staging: Path) -> None:
    receipt = load_json(staging / "receipt.json")
    jar_name = f"httpclient5-{NEW_VERSION}.jar"
    staged_jar = staging / jar_name
    jar_data = staged_jar.read_bytes()
    stage.verify_jar_identity(jar_data)
    parent_name = f"httpclient5-parent-{NEW_VERSION}.pom"
    parent_data = (staging / parent_name).read_bytes()
    stage.verify_parent_pom(parent_data)
    if hashlib.sha512(jar_data).hexdigest() != receipt["jar"]["sha512"]:
        raise ValueError("Staged HttpClient JAR no longer matches its verified receipt")
    if sha256(jar_data) != receipt["jar"]["sha256"]:
        raise ValueError("Staged HttpClient JAR SHA-256 no longer matches its receipt")
    if hashlib.sha512(parent_data).hexdigest() != receipt["parentPom"]["sha512"]:
        raise ValueError("Staged HttpClient parent POM no longer matches its receipt")
    if receipt.get("httpcoreVersion") != CORE_VERSION:
        raise ValueError("Staged HttpClient dependency management does not match bundled HttpCore")

    old_file = root / OLD_PATH
    new_file = root / NEW_PATH
    if not old_file.is_file() or new_file.exists():
        raise ValueError("Expected old HttpClient JAR or empty destination path is missing")
    manifest_path = root / "security/binary-manifest.json"
    manifest = load_json(manifest_path)
    components = manifest.get("components", [])
    by_path = {component["path"]: component for component in components}
    old_component = by_path.get(OLD_PATH)
    epub_component = by_path.get(EPC_PATH)
    if old_component is None or epub_component is None:
        raise ValueError("Binary manifest is missing the HttpClient or EPUBCheck entry")
    if sha256(old_file.read_bytes()) != old_component["sha256"]:
        raise ValueError("Current HttpClient JAR does not match its reviewed manifest hash")

    vendor_dir = root / "tools/validators/epubcheck"
    lib_dir = vendor_dir / "lib"
    epub_jar = vendor_dir / "epubcheck.jar"
    rewritten_epub = rewrite_epubcheck_jar(epub_jar, lib_dir)
    new_client_hash = sha256(jar_data)
    epub_hash = sha256(rewritten_epub)
    checked_on = date.today().isoformat()

    new_component = dict(old_component)
    new_component.update({
        "path": NEW_PATH,
        "sha256": new_client_hash,
        "sizeBytes": len(jar_data),
        "name": "httpclient5",
        "version": NEW_VERSION,
        "checkedOn": checked_on,
        "runtimeVersionVerified": True,
        "sourceURL": receipt["jar"]["url"],
        "sourceKind": "Maven Central SHA-512 verified; embedded Maven metadata and parent dependency management verified",
        "identityEvidence": [
            f"{NEW_PATH} (distribution filename)",
            f"{NEW_PATH}!/META-INF/maven/org.apache.httpcomponents.client5/httpclient5/pom.properties",
            f"{NEW_PATH}!/META-INF/maven/org.apache.httpcomponents.client5/httpclient5/pom.xml",
            f"{parent_name} (httpcore.version={CORE_VERSION})",
        ],
        "embeddedMavenComponents": [{
            "artifactId": "httpclient5",
            "groupId": "org.apache.httpcomponents.client5",
            "version": NEW_VERSION,
        }],
        "group": "org.apache.httpcomponents.client5",
        "purl": f"pkg:maven/org.apache.httpcomponents.client5/httpclient5@{NEW_VERSION}",
    })
    updated_components = []
    for component in components:
        if component["path"] == OLD_PATH:
            continue
        item = dict(component)
        if item["path"] == EPC_PATH:
            item["sha256"] = epub_hash
            item["sizeBytes"] = len(rewritten_epub)
            item["checkedOn"] = checked_on
            item["runtimeVersionVerified"] = True
        elif item["path"] in {
            "tools/validators/epubcheck/lib/httpcore5-5.4.4.jar",
            "tools/validators/epubcheck/lib/httpcore5-h2-5.4.4.jar",
        }:
            item["checkedOn"] = checked_on
            item["runtimeVersionVerified"] = True
        updated_components.append(item)
    updated_components.append(new_component)
    updated_components.sort(key=lambda item: item["path"])
    manifest["components"] = updated_components

    sha_file = root / "tools/CONVERTERS-SHA256.txt"
    sha_lines = sha_file.read_text(encoding="utf-8").splitlines()
    new_sha_lines = []
    seen_old = seen_epub = False
    for line in sha_lines:
        match = re.fullmatch(r"([0-9a-f]{64})\s+(.+)", line.strip())
        if not match:
            if line.strip():
                raise ValueError(f"Unexpected SHA-256 manifest line: {line}")
            continue
        digest, path = match.groups()
        if path == OLD_PATH:
            if digest != old_component["sha256"]:
                raise ValueError("Old HttpClient SHA-256 list disagrees with binary manifest")
            seen_old = True
            continue
        if path == EPC_PATH:
            new_sha_lines.append(f"{epub_hash}  {EPC_PATH}")
            seen_epub = True
            continue
        new_sha_lines.append(line)
    if not seen_old or not seen_epub:
        raise ValueError("Converter SHA-256 list is missing expected old HttpClient or EPUBCheck entry")
    new_sha_lines.append(f"{new_client_hash}  {NEW_PATH}")
    new_sha_lines.sort(key=lambda line: line.split("  ", 1)[1])

    third_party_path = vendor_dir / "THIRD-PARTY.txt"
    third_party = third_party_path.read_text(encoding="utf-8")
    replacements = (
        (f"httpclient5-{OLD_VERSION}.jar", f"httpclient5-{NEW_VERSION}.jar"),
        (f"HttpClient {OLD_VERSION}", f"HttpClient {NEW_VERSION}"),
        (f"httpclient5 {OLD_VERSION}", f"httpclient5 {NEW_VERSION}"),
    )
    replaced = False
    for old, new in replacements:
        if old in third_party:
            third_party = third_party.replace(old, new)
            replaced = True
    if not replaced:
        raise ValueError("THIRD-PARTY.txt has no explicit HttpClient 5.1.3 entry to update")

    # All validation is complete. The workflow commits only if every subsequent gate passes.
    new_file.write_bytes(jar_data)
    old_file.unlink()
    epub_jar.write_bytes(rewritten_epub)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    sha_file.write_text("\n".join(new_sha_lines) + "\n", encoding="utf-8")
    third_party_path.write_text(third_party, encoding="utf-8")

    inventory = subprocess.run(
        [sys.executable, str(root / "tools/vendor-binary-inventory.py"),
         "--check", "--root", str(root), "--sbom", str(root / "security/vendor-bom.cdx.json")],
        cwd=root, text=True, capture_output=True,
    )
    if inventory.returncode:
        raise RuntimeError(
            "Vendor inventory/SBOM validation failed:\n"
            + inventory.stdout + inventory.stderr
        )
    print(inventory.stdout.strip())
    print(
        f"Applied HttpClient {NEW_VERSION}; HttpCore {CORE_VERSION}; "
        f"updated EPUBCheck manifest and vendor hashes."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--staging", required=True, type=Path)
    args = parser.parse_args()
    apply(args.root.resolve(), args.staging.resolve())


if __name__ == "__main__":
    main()
