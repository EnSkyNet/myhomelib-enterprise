#!/usr/bin/env python3
"""Delete only branches already contained in master or explicitly reviewed as superseded.

This is a one-time repository maintenance tool. Unknown non-ancestor branches are
preserved and reported; it never force-pushes or rewrites a commit.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPOSITORY = "EnSkyNet/myhomelib-enterprise"
SUPERSEDED_BRANCHES = {
    # Closed/superseded work: current master contains the equivalent implementation.
    "ci/v34-performance-xvfb": "ca673689fec3537cadb39fb4856ca6c7ceef24a0",
    "ci/v34-portable-build-on-application-changes-20261023": "a9dab118b2e5cac5f5f323a8f526ac8701cf233c",
    "dependabot/github_actions/actions/cache-6.1.0": "5eff2e5097386bc164f25112148a7d56b65a0e5d",
    "dependabot/maven/com.github.luben-zstd-jni-1.5.7-24": "bcd0149d28f8d240bd7c1ced878124087362f7ea",
    "fix/v34-crash-worker-utf8": "edaa3bbedbedf6a413364b8f2363627fde931cbe",
    "fix/v34-validate-recovery-checkpoint-index-integrity-20261031": "4f4e36660cecb6b5b6156b156e7ddfa878dc7131",
    "perf/v34-group-metabib-keyset-export-20261101": "da4927f963395b6b7c3105a31e26a8a9d7bf97b0",
    "test/v34-flibusta-metabib-libid-import-20261101": "126751fc9fbc41c58daf8aedc190a6d0d601ad6f",
    "validation/v34-windows-candidate": "3f79cd0a750eb99cded6710223b80f9d65f6b160",
}

def git(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["git", *args],
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed ({result.returncode}): {result.stderr.strip()}")
    return result

def remote_heads() -> dict[str, str]:
    output = git("ls-remote", "--heads", "origin").stdout
    heads: dict[str, str] = {}
    for line in output.splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[1].startswith("refs/heads/"):
            heads[fields[1][len("refs/heads/"):]] = fields[0]
    return heads

def is_ancestor(commit: str, master: str) -> bool:
    result = git("merge-base", "--is-ancestor", commit, master, check=False)
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        return False
    raise RuntimeError(f"git merge-base failed for {commit}: {result.stderr.strip()}")

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="perform the reviewed remote deletions")
    parser.add_argument("--report", type=Path, default=Path("target/branch-cleanup-report.json"))
    args = parser.parse_args()

    origin = git("remote", "get-url", "origin").stdout.strip()
    if not re.search(r"(?:github\.com[:/])EnSkyNet/myhomelib-enterprise(?:\.git)?$", origin, re.IGNORECASE):
        raise RuntimeError(f"unexpected origin URL: {origin}")

    branch = git("branch", "--show-current").stdout.strip()
    if branch != "master":
        raise RuntimeError(f"cleanup must run from master, not {branch!r}")
    if git("status", "--porcelain").stdout.strip():
        raise RuntimeError("working tree must be clean")

    git("fetch", "origin", "+refs/heads/*:refs/remotes/origin/*", "--prune")
    master_sha = git("rev-parse", "refs/remotes/origin/master").stdout.strip()
    local_sha = git("rev-parse", "HEAD").stdout.strip()
    if local_sha != master_sha:
        raise RuntimeError(f"local master {local_sha} differs from origin/master {master_sha}")

    heads = remote_heads()
    if heads.get("master") != master_sha:
        raise RuntimeError("master changed during branch inventory; retry on the latest master")

    planned: list[dict[str, str]] = []
    preserved: list[dict[str, str]] = []
    for name, sha in sorted(heads.items()):
        if name == "master":
            continue
        if is_ancestor(sha, master_sha):
            planned.append({"name": name, "sha": sha, "reason": "branch tip is already contained in master"})
        elif SUPERSEDED_BRANCHES.get(name) == sha:
            planned.append({"name": name, "sha": sha, "reason": "exact SHA audited as superseded by current master"})
        else:
            reason = "branch SHA changed since review" if name in SUPERSEDED_BRANCHES else "non-ancestor branch requires review"
            preserved.append({"name": name, "sha": sha, "reason": reason})

    report: dict[str, object] = {
        "repository": REPOSITORY,
        "masterSha": master_sha,
        "createdAtUtc": datetime.now(timezone.utc).isoformat(),
        "mode": "execute" if args.execute else "dry-run",
        "planned": planned,
        "preservedBeforeDeletion": preserved,
        "deleted": [],
        "skippedChanged": [],
        "failed": [],
        "remaining": {},
    }

    print(f"Repository: {REPOSITORY}")
    print(f"master: {master_sha}")
    print(f"remote branches: {len(heads)}")
    print(f"safe deletion candidates: {len(planned)}")
    print(f"preserved for review: {len(preserved)}")
    for item in planned:
        print(f"DELETE CANDIDATE  {item['name']}  {item['sha']}  ({item['reason']})")
    for item in preserved:
        print(f"KEEP              {item['name']}  {item['sha']}  ({item['reason']})")

    if args.execute:
        if os.environ.get("MHL_EXECUTE_BRANCH_CLEANUP") != "YES":
            raise RuntimeError("--execute requires MHL_EXECUTE_BRANCH_CLEANUP=YES")
        deleted: list[dict[str, str]] = []
        skipped: list[dict[str, str]] = []
        failed: list[dict[str, str]] = []
        for item in planned:
            name, expected_sha = item["name"], item["sha"]
            latest_sha = remote_heads().get(name)
            if latest_sha is None:
                continue
            if latest_sha != expected_sha:
                skipped.append({"name": name, "sha": latest_sha, "reason": "remote SHA changed immediately before deletion"})
                continue
            result = git("push", "origin", "--delete", name, check=False)
            if result.returncode == 0:
                deleted.append(item)
                print(f"DELETED           {name}")
            else:
                failed.append({"name": name, "sha": expected_sha, "reason": result.stderr.strip()})
                print(f"DELETE FAILED     {name}: {result.stderr.strip()}", file=sys.stderr)
        report["deleted"] = deleted
        report["skippedChanged"] = skipped
        report["failed"] = failed
        report["remaining"] = {k: v for k, v in remote_heads().items() if k != "master"}
        print(f"deleted: {len(deleted)}")
        print(f"skipped due to changed SHA: {len(skipped)}")
        print(f"delete failures: {len(failed)}")
        print(f"remaining non-master branches: {len(report['remaining'])}")

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if args.execute and report["remaining"]:
        print("UNRESOLVED: remaining branches were not deleted; review them before another cleanup run.", file=sys.stderr)
        return 2
    if args.execute and report["failed"]:
        return 1
    if not args.execute:
        print("DRY RUN ONLY — no remote refs changed.")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
