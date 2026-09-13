#!/usr/bin/env python3
"""Safely fetch/ingest a GitHub connected-acceptance artifact for Windows finalization.

Final mode fetches the artifact from a specific successful GitHub workflow run,
verifies the SHA-256 digest declared by the Actions artifact API, then stages only
validated candidate-bound evidence. A local ZIP may be used for rehearsal, but is
explicitly recorded as not remotely digest-verified.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

from evidence_contracts import current_schema, validate_record

from zip_evidence_safety import inspect_open_zip

ROOT = Path(__file__).resolve().parents[1]
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class IngestError(RuntimeError):
    pass


def need(cond: bool, message: str) -> None:
    if not cond:
        raise IngestError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    need(spec is not None and spec.loader is not None, f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def parse_manifest(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_no, raw in enumerate(text.splitlines(), 1):
        if not raw.strip():
            continue
        parts = raw.strip().split(None, 1)
        need(len(parts) == 2, f"candidate manifest line {line_no} is invalid")
        digest, name = parts
        name = name.lstrip("*").replace("\\", "/")
        need(SHA256_RE.fullmatch(digest.lower()) is not None, f"candidate manifest line {line_no} has invalid SHA-256")
        need("/" not in name, f"candidate manifest entry must be a basename: {name!r}")
        need(name not in result, f"duplicate candidate manifest entry: {name}")
        result[name] = digest.lower()
    return result


def validate_integrity_evidence(
    zf: zipfile.ZipFile,
    candidate_prefix: str,
    candidate_files: list[str],
    manifest: dict[str, str],
    github: dict[str, Any],
) -> None:
    integrity_name = "release-candidate-integrity-windows.json"
    sidecar_name = integrity_name + ".sha256"
    sums_name = "release-windows-SHA256SUMS"
    expected = set(manifest) | {"candidate-windows.sha256", sums_name, integrity_name, sidecar_name}
    need(set(candidate_files) == expected, "candidate-windows member set does not match exact evidence contract")

    integrity_blob = zf.read(candidate_prefix + integrity_name)
    sidecar_parts = zf.read(candidate_prefix + sidecar_name).decode("utf-8-sig").strip().split(None, 1)
    need(len(sidecar_parts) == 2 and sidecar_parts[1].lstrip("*") == integrity_name,
         "candidate integrity sidecar is invalid")
    integrity_sha = sha256_bytes(integrity_blob)
    need(sidecar_parts[0].lower() == integrity_sha, "candidate integrity sidecar digest mismatch")
    need(integrity_sha == github["windowsIntegritySha256"], "candidate integrity hash does not match GitHub evidence")
    try:
        record = json.loads(integrity_blob.decode("utf-8-sig"))
    except Exception as exc:  # noqa: BLE001
        raise IngestError(f"candidate integrity JSON invalid: {exc}") from exc
    need(isinstance(record, dict), "candidate integrity JSON root must be an object")
    try:
        validate_record(record, "release-candidate-integrity", label="candidate integrity record")
    except ValueError as exc:
        raise IngestError(str(exc)) from exc
    need(record.get("overall") == "PASS", "candidate integrity record is not PASS")
    need(record.get("platform") == "windows", "candidate integrity platform must be windows")
    need(record.get("projectVersion") == github["windowsIntegrityProjectVersion"],
         "candidate integrity project version mismatch")
    need(str(record.get("candidateSha") or "").lower() == github["candidateSha"] == github["windowsIntegrityCandidateSha"],
         "candidate integrity Git SHA mismatch")
    need(str(record.get("sourceTreeSha256") or "").lower() == github["windowsIntegritySourceTreeSha256"],
         "candidate integrity source-tree digest mismatch")
    need(str(record.get("distManifestSha256") or "").lower() == github["windowsIntegrityDistManifestSha256"],
         "candidate integrity dist-manifest digest mismatch")

    sums_blob = zf.read(candidate_prefix + sums_name)
    sums_sha = sha256_bytes(sums_blob)
    need(sums_sha == github["windowsChecksumsSha256"], "release Windows SHA256SUMS digest mismatch vs GitHub evidence")
    need(str(record.get("sha256sSha256") or "").lower() == sums_sha, "candidate integrity SHA256SUMS digest mismatch")
    release_sums = parse_manifest(sums_blob.decode("utf-8-sig"))
    rows = record.get("distFiles")
    need(isinstance(rows, list) and rows, "candidate integrity distFiles missing")
    need(record.get("distFileCount") == len(rows), "candidate integrity distFileCount mismatch")
    dist: dict[str, dict[str, Any]] = {}
    aggregate = hashlib.sha256()
    for row in rows:
        need(isinstance(row, dict), "candidate integrity distFiles row invalid")
        rel = str(row.get("path") or "").replace("\\", "/")
        digest = str(row.get("sha256") or "").lower()
        size = row.get("size")
        need(rel and "/" not in rel and ".." not in PurePosixPath(rel).parts, f"candidate integrity dist path invalid: {rel!r}")
        need(SHA256_RE.fullmatch(digest) is not None, f"candidate integrity dist digest invalid: {rel}")
        need(isinstance(size, int) and size >= 0, f"candidate integrity dist size invalid: {rel}")
        need(rel not in dist, f"candidate integrity duplicate dist path: {rel}")
        dist[rel] = row
        aggregate.update(f"{digest}  {rel}  {size}\n".encode("utf-8"))
    need(set(dist) == set(release_sums), "candidate integrity dist file set does not match release SHA256SUMS")
    need(aggregate.hexdigest() == github["windowsIntegrityDistManifestSha256"],
         "candidate integrity dist aggregate mismatch")
    for rel, digest in release_sums.items():
        need(str(dist[rel]["sha256"]).lower() == digest, f"candidate integrity/release sums digest mismatch for {rel}")
    for rel, digest in manifest.items():
        need(rel in release_sums and release_sums[rel] == digest, f"candidate manifest is not bound by release SHA256SUMS: {rel}")
        payload = zf.read(candidate_prefix + rel)
        need(len(payload) == int(dist[rel]["size"]), f"candidate integrity size mismatch for {rel}")


def inspect_zip(blob: bytes) -> tuple[zipfile.ZipFile, dict[str, zipfile.ZipInfo]]:
    need(blob, "GitHub acceptance artifact ZIP is empty")
    try:
        zf = zipfile.ZipFile(__import__("io").BytesIO(blob))
    except zipfile.BadZipFile as exc:
        raise IngestError("GitHub acceptance artifact is not a valid ZIP") from exc
    infos = inspect_open_zip(
        zf,
        label="GitHub acceptance artifact",
        error_type=IngestError,
        max_files=50,
        max_member_bytes=1024 * 1024 * 1024,
        max_total_bytes=2 * 1024 * 1024 * 1024,
    )
    return zf, infos


def locate_root(names: set[str]) -> str:
    hits = [n for n in names if PurePosixPath(n).name == "github-connected-acceptance.json"]
    need(len(hits) == 1, f"expected exactly one github-connected-acceptance.json, found {len(hits)}")
    hit = PurePosixPath(hits[0])
    return "" if str(hit.parent) == "." else str(hit.parent).rstrip("/") + "/"


def validate_and_stage(blob: bytes, destination: Path, *, remote: dict[str, Any] | None = None) -> dict[str, Any]:
    github_mod = load_module("v71_final_for_ingest", ROOT / "tools/v71-final-external-acceptance-check.py")
    zf, infos = inspect_zip(blob)
    with zf:
        names = set(infos)
        prefix = locate_root(names)
        required = {
            prefix + "github-connected-acceptance.json",
            prefix + "github-connected-acceptance.md",
            prefix + "acceptance-harness.sha256",
            prefix + "candidate-windows/candidate-windows.sha256",
        }
        missing = required - names
        need(not missing, "GitHub acceptance artifact missing required member(s): " + ", ".join(sorted(missing)))

        candidate_prefix = prefix + "candidate-windows/"
        candidate_files = sorted(n[len(candidate_prefix):] for n in names if n.startswith(candidate_prefix))
        need(all("/" not in n for n in candidate_files), "candidate-windows must not contain nested directories")
        manifest_name = "candidate-windows.sha256"
        need(manifest_name in candidate_files, "candidate-windows.sha256 missing")
        manifest = parse_manifest(zf.read(candidate_prefix + manifest_name).decode("utf-8-sig"))
        need(len(manifest) == 3, "candidate-windows.sha256 must contain exactly MSI, EXE and portable entries")
        msi = [n for n in manifest if n.lower().endswith(".msi")]
        exe = [n for n in manifest if n.lower().endswith(".exe")]
        portable = [n for n in manifest if n.lower().endswith(".zip")]
        need(len(msi) == len(exe) == len(portable) == 1, "candidate manifest must contain exactly one MSI, EXE and portable ZIP")
        for name, digest in manifest.items():
            actual = sha256_bytes(zf.read(candidate_prefix + name))
            need(actual == digest, f"candidate manifest SHA-256 mismatch for {name}")

        with tempfile.TemporaryDirectory(prefix="myhomelib-github-ingest-") as td:
            temp = Path(td)
            gh_json = temp / "github-connected-acceptance.json"
            gh_json.write_bytes(zf.read(prefix + "github-connected-acceptance.json"))
            gh = github_mod.verify_github_json(gh_json).details
            validate_integrity_evidence(zf, candidate_prefix, candidate_files, manifest, gh)
            harness_blob = zf.read(prefix + "acceptance-harness.sha256")
            harness_sha = sha256_bytes(harness_blob)
            need(harness_sha == gh["acceptanceHarnessManifestSha256"], "acceptance harness manifest hash does not match GitHub evidence")
            need(manifest[msi[0]] == gh["windowsMsiSha256"], "candidate MSI hash does not match GitHub evidence")
            need(manifest[exe[0]] == gh["windowsExeSha256"], "candidate EXE hash does not match GitHub evidence")
            need(manifest[portable[0]] == gh["windowsPortableSha256"], "candidate portable hash does not match GitHub evidence")
            if remote:
                need(str(gh["candidateSha"]) == str(remote["candidateSha"]), "workflow head SHA does not match evidence candidate SHA")
                need(str(gh["repository"]) == str(remote["repository"]), "workflow repository does not match evidence repository")
                need(sha256_bytes(blob) == str(remote["artifactZipSha256"]), "downloaded acceptance artifact hash changed after GitHub digest verification")
                need(str(remote["declaredDigest"]) == "sha256:" + str(remote["artifactZipSha256"]), "GitHub declared digest/verified artifact hash mismatch")

            stage = temp / "stage"
            stage.mkdir()
            (stage / "candidate-windows").mkdir()
            (stage / "github-connected-acceptance.json").write_bytes(zf.read(prefix + "github-connected-acceptance.json"))
            (stage / "github-connected-acceptance.md").write_bytes(zf.read(prefix + "github-connected-acceptance.md"))
            (stage / "acceptance-harness.sha256").write_bytes(harness_blob)
            for name in candidate_files:
                (stage / "candidate-windows" / name).write_bytes(zf.read(candidate_prefix + name))

            record = {
                "schemaVersion": current_schema("github-connected-acceptance-artifact-ingest"),
                "scenario": "github-connected-acceptance-artifact-ingest",
                "overall": "PASS",
                "candidateSha": gh["candidateSha"],
                "repository": gh["repository"],
                "releaseRunId": gh["releaseRunId"],
                "releaseRunUrl": gh["releaseRunUrl"],
                "artifactZipSha256": sha256_bytes(blob),
                "remoteDigestVerified": bool(remote),
                "acceptanceRunId": remote.get("acceptanceRunId") if remote else None,
                "acceptanceRunUrl": remote.get("acceptanceRunUrl") if remote else None,
                "githubArtifactId": remote.get("artifactId") if remote else None,
                "githubArtifactName": remote.get("artifactName") if remote else None,
                "githubDeclaredDigest": remote.get("declaredDigest") if remote else None,
                "windowsMsiSha256": gh["windowsMsiSha256"],
                "windowsExeSha256": gh["windowsExeSha256"],
                "windowsPortableSha256": gh["windowsPortableSha256"],
                "windowsChecksumsSha256": gh["windowsChecksumsSha256"],
                "windowsIntegrityProjectVersion": gh["windowsIntegrityProjectVersion"],
                "windowsIntegrityCandidateSha": gh["windowsIntegrityCandidateSha"],
                "windowsIntegritySha256": gh["windowsIntegritySha256"],
                "windowsIntegritySourceTreeSha256": gh["windowsIntegritySourceTreeSha256"],
                "windowsIntegrityDistManifestSha256": gh["windowsIntegrityDistManifestSha256"],
                "acceptanceHarnessManifestSha256": gh["acceptanceHarnessManifestSha256"],
            }
            (stage / "github-connected-acceptance-ingest.json").write_text(
                json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
            )

            destination = destination.resolve()
            destination.parent.mkdir(parents=True, exist_ok=True)
            backup = destination.with_name(destination.name + ".previous-ingest")
            if backup.exists():
                shutil.rmtree(backup)
            try:
                if destination.exists():
                    destination.replace(backup)
                shutil.copytree(stage, destination)
                if backup.exists():
                    shutil.rmtree(backup)
            except Exception:
                if destination.exists():
                    shutil.rmtree(destination, ignore_errors=True)
                if backup.exists():
                    backup.replace(destination)
                raise
            return record


def fetch_remote(repo: str, run_id: int, token: str | None, api_url: str) -> tuple[bytes, dict[str, Any]]:
    gh_mod = load_module("github_connected_for_ingest", ROOT / "tools/github-connected-acceptance.py")
    client = gh_mod.GitHubClient(repo, token, api_url)
    run = client.json(gh_mod.repo_path(repo, f"/actions/runs/{run_id}"))
    need(run.get("status") == "completed" and run.get("conclusion") == "success", f"acceptance run {run_id} is not successful/completed")
    path = str(run.get("path") or "")
    need(path.endswith("/.github/workflows/github-acceptance.yml") or path == ".github/workflows/github-acceptance.yml",
         f"run {run_id} is not github-acceptance.yml (path={path!r})")
    head_sha = str(run.get("head_sha") or "").lower()
    need(re.fullmatch(r"[0-9a-f]{40}", head_sha) is not None, f"acceptance run {run_id} has invalid head_sha")
    payload = client.json(gh_mod.repo_path(repo, f"/actions/runs/{run_id}/artifacts?per_page=100"))
    artifacts = payload.get("artifacts") or []
    expected_prefix = f"github-connected-acceptance-{run_id}-"
    matches = [a for a in artifacts if str(a.get("name") or "").startswith(expected_prefix) and not a.get("expired")]
    need(len(matches) == 1, f"acceptance run {run_id}: expected exactly one non-expired connected-acceptance artifact, found {len(matches)}")
    artifact = matches[0]
    artifact_url = str(artifact.get("archive_download_url") or "")
    need(artifact_url.startswith("https://") or artifact_url.startswith("http://"), f"acceptance run {run_id}: artifact download URL missing/invalid")
    blob = client.download_artifact(artifact_url)
    actual = gh_mod.verify_github_artifact_digest(blob, str(artifact.get("digest") or ""), "GitHub connected acceptance artifact")
    return blob, {
        "acceptanceRunId": run_id,
        "acceptanceRunUrl": str(run.get("html_url") or ""),
        "candidateSha": head_sha,
        "repository": repo,
        "artifactId": artifact.get("id"),
        "artifactName": artifact.get("name"),
        "declaredDigest": str(artifact.get("digest") or "").lower(),
        "artifactZipSha256": actual,
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser()
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument("--artifact-zip", type=Path, help="local rehearsal ZIP; remote digest is not verified")
    source.add_argument("--acceptance-run-id", type=int, help="GitHub connected acceptance workflow run id")
    p.add_argument("--repo", default=os.getenv("GITHUB_REPOSITORY"))
    p.add_argument("--token", default=os.getenv("GITHUB_TOKEN") or os.getenv("GH_TOKEN"))
    p.add_argument("--api-url", default=os.getenv("GITHUB_API_URL", "https://api.github.com"))
    p.add_argument("--out-dir", type=Path, default=Path("target/github-connected-acceptance"))
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    try:
        if args.acceptance_run_id:
            need(bool(args.repo), "--repo/GITHUB_REPOSITORY is required with --acceptance-run-id")
            blob, remote = fetch_remote(args.repo, args.acceptance_run_id, args.token, args.api_url)
        else:
            need(args.artifact_zip is not None and args.artifact_zip.is_file(), f"artifact ZIP not found: {args.artifact_zip}")
            blob = args.artifact_zip.read_bytes()
            remote = None
        record = validate_and_stage(blob, args.out_dir, remote=remote)
        print("GitHub connected acceptance artifact ingest: PASS")
        print(f"- candidate: {record['candidateSha']}")
        print(f"- remote digest verified: {record['remoteDigestVerified']}")
        print(f"- staged: {args.out_dir}")
        return 0
    except (IngestError, OSError, zipfile.BadZipFile) as exc:
        print(f"GitHub connected acceptance artifact ingest: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
