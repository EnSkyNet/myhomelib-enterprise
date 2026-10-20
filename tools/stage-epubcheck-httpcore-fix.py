#!/usr/bin/env python3
"""Stage reviewed EPUBCheck HttpCore replacement JARs without modifying originals.

Usage:
  python tools/stage-epubcheck-httpcore-fix.py --output /tmp/epubcheck-httpcore
Then verify SHA-256, Java compatibility, EPUBCheck validation and update binary
manifest/SBOM before committing replacements. No security suppression is allowed.
"""
import argparse
import hashlib
from pathlib import Path
import urllib.request
import zipfile

ARTIFACTS = (
    ("org.apache.httpcomponents.core5", "httpcore5", "5.4.4"),
    ("org.apache.httpcomponents.core5", "httpcore5-h2", "5.4.4"),
)
REPOSITORY = "https://repo.maven.apache.org/maven2"

def verified_artifact(group, name, version, output):
    prefix = f"{REPOSITORY}/{group.replace('.', '/')}/{name}/{version}/{name}-{version}.jar"
    with urllib.request.urlopen(prefix + ".sha256", timeout=45) as response:
        expected = response.read(512).decode("ascii").strip().split()[0].lower()
    if len(expected) != 64 or any(c not in "0123456789abcdef" for c in expected):
        raise ValueError(f"Missing valid Maven Central SHA-256 for {name}")
    with urllib.request.urlopen(prefix, timeout=120) as response:
        data = response.read(32 * 1024 * 1024 + 1)
    if len(data) > 32 * 1024 * 1024:
        raise ValueError(f"Unreasonably large artifact: {name}")
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected:
        raise ValueError(f"SHA-256 mismatch for {name}: expected {expected}, got {digest}")
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        meta = f"META-INF/maven/{group}/{name}/pom.properties"
        props = archive.read(meta).decode("utf-8")
        assert f"version={version}" in props.splitlines(), f"Unexpected {name} artifact version"
        assert f"artifactId={name}" in props.splitlines(), f"Unexpected artifactId for {name}"
    output.mkdir(parents=True, exist_ok=True)
    dest = output / f"{name}-{version}.jar"
    if dest.exists():
        raise FileExistsError(f"Refusing to overwrite staged artifact: {dest}")
    dest.write_bytes(data)
    return dest, digest

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--output", required=True, type=Path, help="New empty staging directory (outside tracked vendor tree)")
    args = ap.parse_args()
    if args.output.exists() and any(args.output.iterdir()):
        ap.error("staging directory must be empty; refusing to overwrite any existing files")
    for group, name, version in ARTIFACTS:
        path, digest = verified_artifact(group, name, version, args.output)
        print(f"{digest}  {path}")
    print("STAGED ONLY; NOT RELEASE READY. Replace matching old JARs together,")
    print("update tools/CONVERTERS-SHA256.txt and security/binary-manifest.json,")
    print("regenerate security/vendor-bom.cdx.json, test EPUBCheck and run blocking SCA.")

if __name__ == "__main__":
    main()
