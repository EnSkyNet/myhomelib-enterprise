#!/usr/bin/env python3
"""Generate/verify a deterministic MyHomeLib release-candidate integrity record.

This is an integrity manifest, not a signature or SLSA provenance claim. It binds
one source tree and one platform dist directory to an exact Git candidate SHA.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path, PurePosixPath
import xml.etree.ElementTree as ET

from evidence_contracts import current_schema, validate_record

SHA40 = re.compile(r"^[0-9a-f]{40}$")
SHA64 = re.compile(r"^[0-9a-f]{64}$")
EXCLUDED_PARTS = {".git", ".mvn", "target", "dist", "__pycache__", ".idea", ".gradle"}
CRITICAL_POLICY_PATHS = (
    "pom.xml",
    ".github/workflows/ci-pr.yml",
    ".github/workflows/ci-release.yml",
    ".github/workflows/codeql.yml",
    ".github/workflows/github-acceptance.yml",
    "tools/stage23-cross-platform-release-check.py",
    "tools/supply-chain-policy-check.py",
    "tools/github-connected-acceptance.py",
    "tools/external-acceptance-readiness.py",
    "tools/windows-acceptance-evidence-check.py",
    "tools/v71-final-external-acceptance-check.py",
    "tools/evidence_contracts.py",
)

class IntegrityError(RuntimeError):
    pass

def need(cond: bool, msg: str) -> None:
    if not cond:
        raise IntegrityError(msg)

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def version(root: Path) -> str:
    node = ET.parse(root / "pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    value = node.findtext("m:version", namespaces=ns)
    need(bool(value and value.strip()), "pom.xml project version missing")
    return str(value).strip()

def safe_rel(value: str) -> str:
    rel = value.replace("\\", "/")
    p = PurePosixPath(rel)
    need(rel and not p.is_absolute() and ".." not in p.parts, f"unsafe relative path: {value!r}")
    return rel

def source_rows(root: Path) -> list[dict[str, object]]:
    rows = []
    for p in sorted((x for x in root.rglob("*") if x.is_file()), key=lambda x: x.relative_to(root).as_posix()):
        rel = p.relative_to(root).as_posix()
        if any(part in EXCLUDED_PARTS for part in PurePosixPath(rel).parts):
            continue
        rows.append({"path": safe_rel(rel), "size": p.stat().st_size, "sha256": sha256_file(p)})
    need(rows, "source tree contains no files")
    return rows

def aggregate(rows: list[dict[str, object]]) -> str:
    h = hashlib.sha256()
    for row in rows:
        line = f"{row['sha256']}  {row['path']}  {row['size']}\n".encode("utf-8")
        h.update(line)
    return h.hexdigest()

def parse_sums(path: Path) -> dict[str, str]:
    need(path.is_file(), f"required checksum manifest missing: {path}")
    result: dict[str, str] = {}
    for n, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not raw.strip():
            continue
        parts = raw.strip().split(None, 1)
        need(len(parts) == 2, f"SHA256SUMS line {n} invalid")
        digest, rel = parts
        rel = safe_rel(rel.lstrip("*"))
        need(SHA64.fullmatch(digest.lower()) is not None, f"SHA256SUMS line {n} digest invalid")
        need(rel not in result, f"duplicate SHA256SUMS entry: {rel}")
        result[rel] = digest.lower()
    need(result, "SHA256SUMS has no entries")
    return result

def dist_rows(dist: Path) -> list[dict[str, object]]:
    sums = parse_sums(dist / "SHA256SUMS")
    rows = []
    for rel, expected in sorted(sums.items()):
        p = dist / Path(*PurePosixPath(rel).parts)
        need(p.is_file(), f"checksum target missing: {rel}")
        actual = sha256_file(p)
        need(actual == expected, f"checksum mismatch for {rel}")
        rows.append({"path": rel, "size": p.stat().st_size, "sha256": actual})
    # Fail closed on release payload not represented by SHA256SUMS.
    actual_files = {
        p.relative_to(dist).as_posix() for p in dist.rglob("*")
        if p.is_file() and p.name != "SHA256SUMS"
    }
    need(actual_files == set(sums), "dist file set does not exactly match SHA256SUMS")
    return rows

def build_record(root: Path, dist: Path, platform: str, candidate_sha: str) -> dict[str, object]:
    sha = candidate_sha.strip().lower()
    need(SHA40.fullmatch(sha) is not None, "candidate SHA must be a full 40-character Git SHA")
    platform = platform.strip().lower()
    need(platform in {"linux", "windows", "macos"}, "platform must be linux/windows/macos")
    source = source_rows(root)
    distfiles = dist_rows(dist)
    critical = []
    source_by_path = {str(r["path"]): r for r in source}
    for rel in CRITICAL_POLICY_PATHS:
        need(rel in source_by_path, f"critical policy file missing from source manifest: {rel}")
        critical.append(source_by_path[rel])
    return {
        "schemaVersion": current_schema("release-candidate-integrity"),
        "scenario": "release-candidate-integrity",
        "overall": "PASS",
        "projectVersion": version(root),
        "candidateSha": sha,
        "platform": platform,
        "sourceFileCount": len(source),
        "sourceTreeSha256": aggregate(source),
        "criticalPolicyFiles": critical,
        "distFileCount": len(distfiles),
        "distManifestSha256": aggregate(distfiles),
        "sha256sSha256": sha256_file(dist / "SHA256SUMS"),
        "distFiles": distfiles,
    }

def write_record(record: dict[str, object], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    data = (json.dumps(record, indent=2, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    tmp = out.with_name(out.name + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, out)
    digest = hashlib.sha256(data).hexdigest()
    out.with_suffix(out.suffix + ".sha256").write_text(f"{digest}  {out.name}\n", encoding="utf-8")

def verify_record(record_path: Path, root: Path, dist: Path, platform: str, candidate_sha: str) -> dict[str, object]:
    need(record_path.is_file(), f"integrity record missing: {record_path}")
    current = json.loads(record_path.read_text(encoding="utf-8"))
    validate_record(current, "release-candidate-integrity", label="release candidate integrity record")
    expected = build_record(root, dist, platform, candidate_sha)
    need(current == expected, "release candidate integrity record does not match current source/dist/candidate")
    sidecar = record_path.with_suffix(record_path.suffix + ".sha256")
    need(sidecar.is_file(), "integrity sidecar missing")
    parts = sidecar.read_text(encoding="utf-8-sig").strip().split(None, 1)
    need(len(parts) == 2 and parts[1].lstrip("*") == record_path.name, "integrity sidecar invalid")
    need(parts[0].lower() == sha256_file(record_path), "integrity sidecar digest mismatch")
    return current

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--dist", default="dist")
    ap.add_argument("--platform", required=True, choices=("linux", "windows", "macos"))
    ap.add_argument("--candidate-sha", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args(argv)
    root = Path(args.root).resolve()
    dist = (root / args.dist).resolve() if not Path(args.dist).is_absolute() else Path(args.dist).resolve()
    out = Path(args.out).resolve()
    need(root.is_dir(), f"source root missing: {root}")
    need(dist.is_dir(), f"dist directory missing: {dist}")
    if args.verify:
        record = verify_record(out, root, dist, args.platform, args.candidate_sha)
        print(f"RELEASE CANDIDATE INTEGRITY: PASS ({record['platform']} {record['candidateSha']})")
    else:
        record = build_record(root, dist, args.platform, args.candidate_sha)
        write_record(record, out)
        print(f"RELEASE CANDIDATE INTEGRITY: PASS ({record['platform']} {record['candidateSha']}) -> {out}")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"RELEASE CANDIDATE INTEGRITY: FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
