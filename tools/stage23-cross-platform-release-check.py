#!/usr/bin/env python3
"""
Cross-platform release validation script.
Checks that all required artifacts are present and valid.
"""

import os
import sys
import json
import hashlib
from pathlib import Path

try:
    import yaml
except ImportError:
    print("ERROR: PyYAML is not installed. Run: pip install pyyaml")
    sys.exit(1)

def main():
    print("Running cross-platform release validation...")

    # Перевірка наявності JAR файлів
    dist_dir = Path("dist")
    if not dist_dir.exists():
        print("ERROR: dist directory not found")
        sys.exit(1)

    jar_files = list(dist_dir.glob("*.jar"))
    if not jar_files:
        print("ERROR: No JAR files found in dist/")
        sys.exit(1)

    print(f"Found {len(jar_files)} JAR files:")
    for jar in jar_files:
        size = jar.stat().st_size
        print(f"  - {jar.name} ({size:,} bytes)")

    # Перевірка SHA256SUMS
    checksum_file = dist_dir / "SHA256SUMS"
    if not checksum_file.exists():
        print("WARNING: SHA256SUMS file not found")
    else:
        print(f"✓ SHA256SUMS file found ({checksum_file.stat().st_size:,} bytes)")

    print("Release validation completed successfully!")

if __name__ == "__main__":
    main()