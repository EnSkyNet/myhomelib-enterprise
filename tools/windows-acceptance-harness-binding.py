#!/usr/bin/env python3
"""Bind the Windows acceptance harness to the exact GitHub release candidate checkout.

Connected GitHub acceptance writes a SHA-256 manifest for every script that can
influence final Windows acceptance. The Windows host must verify its local copy
against that manifest before any candidate PASS evidence is produced.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
CRITICAL_FILES = (
    "tools/windows-acceptance-harness-binding.py",
    "tools/windows-acceptance-host.ps1",
    "tools/github-acceptance-artifact-ingest.py",
    "tools/v71-windows-acceptance-start.ps1",
    "tools/windows-bound-packaging-acceptance.ps1",
    "tools/windows-installer-acceptance.ps1",
    "smoke-portable.ps1",
    "tools/windows-release-desktop-acceptance.ps1",
    "tools/windows-ui-acceptance.ps1",
    "tools/windows-acceptance-evidence-check.py",
    "tools/windows-final-evidence-pack.ps1",
    "tools/v71-finalize-external-acceptance.ps1",
    "tools/v71-final-external-acceptance-check.py",
    "tools/v71-final-evidence-bundle-check.py",
)


class BindingError(RuntimeError):
    pass


def need(cond: bool, message: str) -> None:
    if not cond:
        raise BindingError(message)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def build_manifest(root: Path = ROOT) -> dict[str, str]:
    result: dict[str, str] = {}
    for rel in CRITICAL_FILES:
        path = root / rel
        need(path.is_file(), f"acceptance harness file missing: {rel}")
        result[rel] = sha256_file(path)
    return result


def manifest_text(entries: dict[str, str]) -> str:
    return "".join(f"{entries[name]}  {name}\n" for name in CRITICAL_FILES)


def write_manifest(path: Path, root: Path = ROOT) -> str:
    entries = build_manifest(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(manifest_text(entries), encoding="utf-8")
    return sha256_file(path)


def parse_manifest(path: Path) -> dict[str, str]:
    need(path.is_file(), f"acceptance harness manifest not found: {path}")
    result: dict[str, str] = {}
    for line_no, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not raw.strip():
            continue
        parts = raw.strip().split(None, 1)
        need(len(parts) == 2, f"harness manifest line {line_no} is invalid")
        digest, rel = parts
        digest = digest.lower()
        rel = rel.lstrip("*").replace("\\", "/")
        need(SHA256_RE.fullmatch(digest) is not None, f"harness manifest line {line_no} has invalid SHA-256")
        need(rel not in result, f"duplicate harness manifest entry: {rel}")
        result[rel] = digest
    need(set(result) == set(CRITICAL_FILES), "acceptance harness manifest member set does not match the required harness contract")
    return result


def verify_manifest(path: Path, root: Path = ROOT) -> dict[str, str]:
    expected = parse_manifest(path)
    actual = build_manifest(root)
    for rel in CRITICAL_FILES:
        need(actual[rel] == expected[rel], f"local acceptance harness hash mismatch: {rel}")
    return actual


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser()
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write-manifest", type=Path)
    mode.add_argument("--verify-manifest", type=Path)
    p.add_argument("--root", type=Path, default=ROOT)
    p.add_argument("--candidate-sha")
    p.add_argument("--out-json", type=Path)
    args = p.parse_args(argv or sys.argv[1:])
    try:
        if args.write_manifest:
            manifest_sha = write_manifest(args.write_manifest, args.root)
            print(f"Windows acceptance harness manifest: {args.write_manifest}")
            print(f"SHA-256: {manifest_sha}")
            return 0

        entries = verify_manifest(args.verify_manifest, args.root)
        manifest_sha = sha256_file(args.verify_manifest)
        if args.out_json:
            args.out_json.parent.mkdir(parents=True, exist_ok=True)
            payload = {
                "schemaVersion": 1,
                "scenario": "windows-acceptance-harness-binding",
                "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "overall": "PASS",
                "candidateSha": args.candidate_sha,
                "manifestSha256": manifest_sha,
                "fileCount": len(entries),
                "files": [{"path": rel, "sha256": entries[rel]} for rel in CRITICAL_FILES],
            }
            args.out_json.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        print("Windows acceptance harness binding: PASS")
        print(f"- manifest SHA-256: {manifest_sha}")
        print(f"- files: {len(entries)}")
        return 0
    except (BindingError, OSError) as exc:
        print(f"Windows acceptance harness binding: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
