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
    # Maven Central offers SHA-1 for older artifacts; prefer SHA-512 when published.
    import urllib.error
    checksum = None
    for suffix, algo, width in ((".sha512", "sha512", 128), (".sha1", "sha1", 40)):
        try:
            with urllib.request.urlopen(prefix + suffix, timeout=45) as response:
                expected = response.read(512).decode("ascii").strip().split()[0].lower()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                continue
            raise
        if len(expected) != width or any(c not in "0123456789abcdef" for c in expected):
            raise ValueError(f"Malformed published {algo} for {name}")
        checksum = (algo, expected)
        break
    if checksum is None:
        raise ValueError(f"No checksum published for {name}")
    with urllib.request.urlopen(prefix, timeout=120) as response:
        data = response.read(32 * 1024 * 1024 + 1)
    if len(data) > 32 * 1024 * 1024:
        raise ValueError(f"Unreasonably large artifact: {name}")
    algo, expected = checksum
    if hashlib.new(algo, data).hexdigest() != expected:
        raise ValueError(f"Published checksum mismatch for {name}: {algo}")
    digest = hashlib.sha256(data).hexdigest()
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        meta = f"META-INF/maven/{group}/{name}/pom.properties"
        props = archive.read(meta).decode("utf-8")
        if f"version={version}" not in props.splitlines() or f"artifactId={name}" not in props.splitlines():
            raise ValueError(f"Unexpected embedded artifact identity: {name}")
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
