#!/usr/bin/env python3
"""Apply a reviewed HttpCore replacement to the isolated EPUBCheck vendor tree.

Called only after the staging utility has checked Maven Central checksums and
embedded artifact metadata. Updates all tracked inventories as a unit in Git.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OLD_VERSION, NEW_VERSION = "5.1.3", "5.4.4"
ARTIFACTS = ("httpcore5", "httpcore5-h2")
VENDOR = Path("tools/validators/epubcheck/lib")
MANIFEST = Path("security/binary-manifest.json")
BOM = Path("security/vendor-bom.cdx.json")
SHA_LIST = Path("tools/CONVERTERS-SHA256.txt")

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def apply(root, staging):
    original = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    components = original["components"]
    edits = []
    for artifact in ARTIFACTS:
        old_path = (VENDOR / f"{artifact}-{OLD_VERSION}.jar").as_posix()
        new_path = (VENDOR / f"{artifact}-{NEW_VERSION}.jar").as_posix()
        matches = [c for c in components if c["path"] == old_path]
        if len(matches) != 1:
            raise ValueError(f"Missing/duplicate reviewed vendor component: {old_path}")
        entry = matches[0]
        source = root / old_path
        replacement = staging / f"{artifact}-{NEW_VERSION}.jar"
        if not source.is_file() or sha256(source) != entry["sha256"]:
            raise ValueError(f"Existing vendored artifact hash drift: {old_path}")
        if not replacement.is_file() or replacement.is_symlink():
            raise ValueError(f"Missing staged artifact: {replacement}")
        if (root / new_path).exists():
            raise ValueError(f"Refusing to overwrite vendor file: {new_path}")
        data = replacement.read_bytes()
        if not data.startswith(b"PK\x03\x04") or not data:
            raise ValueError(f"Invalid staged JAR: {replacement}")
        edits.append((entry, source, root / new_path, data, hashlib.sha256(data).hexdigest()))
    digest_lines = (root / SHA_LIST).read_text(encoding="utf-8").splitlines()
    for entry, source, destination, data, digest in edits:
        old_relative = entry["path"]
        old_line = f"{entry['sha256']}  {old_relative}"
        new_relative = destination.relative_to(root).as_posix()
        if digest_lines.count(old_line) != 1:
            raise ValueError(f"SHA list mismatch for {old_relative}")
        digest_lines[digest_lines.index(old_line)] = f"{digest}  {new_relative}"
    # All input data is validated before mutating the tracked tree.
    for entry, source, destination, data, digest in edits:
        destination.write_bytes(data)
        source.unlink()
        name = entry["name"]
        entry["path"] = destination.relative_to(root).as_posix()
        entry["sha256"] = digest
        entry["sizeBytes"] = len(data)
        entry["version"] = NEW_VERSION
        entry["checkedOn"] = "2026-10-20"
        entry["sourceURL"] = f"https://repo.maven.apache.org/maven2/org/apache/httpcomponents/core5/{name}/{NEW_VERSION}/"
        entry["sourceKind"] = "Maven Central SHA-256 verified, embedded Maven metadata verified"
        entry["identityEvidence"] = [
            f"{entry['path']} (distribution filename)",
            f"{entry['path']}!/META-INF/maven/org.apache.httpcomponents.core5/{name}/pom.properties"
        ]
        entry["embeddedMavenComponents"] = [{
            "artifactId": name, "groupId": "org.apache.httpcomponents.core5", "version": NEW_VERSION
        }]
        entry["purl"] = f"pkg:maven/org.apache.httpcomponents.core5/{name}@{NEW_VERSION}"
        entry["runtimeVersionVerified"] = False
    (root / MANIFEST).write_text(json.dumps(original, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    (root / SHA_LIST).write_text("\n".join(digest_lines)+"\n", encoding="utf-8")
    # Reuse canonical SBOM generator; never hand-edit a stale checked-in SBOM.
    import subprocess
    import sys
    subprocess.run([sys.executable, str(root / "tools/vendor-binary-inventory.py"), "--check",
                    "--sbom", str(root / BOM)], cwd=root, check=True)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--staging", type=Path, required=True)
    args = p.parse_args()
    apply(ROOT, args.staging)

if __name__ == "__main__":
    main()
