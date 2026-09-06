#!/usr/bin/env python3
"""Connected GitHub acceptance for MHL-010 / MHL-017 / MHL-018 / MHL-019.

The default mode is intentionally fail-closed and requires live GitHub evidence:
- default-branch rules require the PR CI ``Fast gate`` status check;
- a minimum sample of successful PR ``Fast gate`` jobs has median duration <= 10 min;
- a successful CI Release run exposes a non-expired supply-chain artifact containing
  CycloneDX JSON/XML SBOMs and Dependency-Check report evidence;
- a recent successful CodeQL analysis exists for the default branch and no open
  High/Critical code-scanning alerts are present.

``--codeql-release-gate-only`` is used by release CI so the same tested policy
that is accepted here is also the policy that blocks packaging.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import importlib.util
import re
import os
import statistics
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

API_VERSION = "2026-03-10"
FAST_GATE_NAMES = {"fast gate", "pr ci / fast gate"}
BLOCKING_CODEQL_SEVERITIES = {"critical", "high"}
ROOT = Path(__file__).resolve().parents[1]


def load_harness_binding_module():
    path = ROOT / "tools/windows-acceptance-harness-binding.py"
    spec = importlib.util.spec_from_file_location("windows_acceptance_harness_binding", path)
    need(spec is not None and spec.loader is not None, f"cannot load acceptance harness binding helper: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class AcceptanceError(RuntimeError):
    pass


@dataclass
class Check:
    id: str
    status: str
    summary: str
    details: dict[str, Any]


def need(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceError(message)


def parse_iso(value: str | None) -> datetime:
    need(bool(value), "missing timestamp")
    text = str(value)
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def seconds_between(started_at: str | None, completed_at: str | None) -> float:
    return (parse_iso(completed_at) - parse_iso(started_at)).total_seconds()


def normalize_context(value: str) -> str:
    return " ".join(value.strip().lower().split())


def required_status_contexts_from_branch_rules(rules: Iterable[dict[str, Any]]) -> list[str]:
    contexts: list[str] = []
    for rule in rules:
        if rule.get("type") != "required_status_checks":
            continue
        params = rule.get("parameters") or {}
        for item in params.get("required_status_checks") or []:
            if isinstance(item, dict) and item.get("context"):
                contexts.append(str(item["context"]))
    return contexts


def required_status_contexts_from_legacy(payload: dict[str, Any]) -> list[str]:
    contexts = [str(x) for x in payload.get("contexts") or [] if x]
    contexts.extend(
        str(item["context"])
        for item in payload.get("checks") or []
        if isinstance(item, dict) and item.get("context")
    )
    return contexts


def has_fast_gate_context(contexts: Iterable[str]) -> bool:
    return any(normalize_context(x) in FAST_GATE_NAMES for x in contexts)


def fast_gate_durations(jobs_by_run: Iterable[tuple[int, Iterable[dict[str, Any]]]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for run_id, jobs in jobs_by_run:
        matches = [j for j in jobs if normalize_context(str(j.get("name") or "")) == "fast gate"]
        need(len(matches) == 1, f"PR run {run_id}: expected exactly one 'Fast gate' job, found {len(matches)}")
        job = matches[0]
        need(job.get("status") == "completed", f"PR run {run_id}: Fast gate is not completed")
        need(job.get("conclusion") == "success", f"PR run {run_id}: Fast gate conclusion is {job.get('conclusion')!r}")
        duration = seconds_between(job.get("started_at"), job.get("completed_at"))
        need(duration >= 0, f"PR run {run_id}: negative Fast gate duration")
        rows.append({
            "runId": run_id,
            "jobId": job.get("id"),
            "startedAt": job.get("started_at"),
            "completedAt": job.get("completed_at"),
            "durationSeconds": round(duration, 3),
            "htmlUrl": job.get("html_url"),
        })
    return rows


def evaluate_fast_gate_runtime(rows: list[dict[str, Any]], min_runs: int, max_median_seconds: float) -> float:
    need(len(rows) >= min_runs, f"need at least {min_runs} successful PR Fast gate samples, found {len(rows)}")
    durations = [float(x["durationSeconds"]) for x in rows]
    median = float(statistics.median(durations))
    need(median <= max_median_seconds,
         f"Fast gate median {median:.1f}s exceeds {max_median_seconds:.1f}s acceptance limit")
    return median


def blocking_code_scanning_alerts(alerts: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    blocked: list[dict[str, Any]] = []
    for alert in alerts:
        if alert.get("state") not in (None, "open"):
            continue
        rule = alert.get("rule") or {}
        severity = str(rule.get("security_severity_level") or "").lower()
        if severity in BLOCKING_CODEQL_SEVERITIES:
            blocked.append({
                "number": alert.get("number"),
                "ruleId": rule.get("id"),
                "severity": severity,
                "htmlUrl": alert.get("html_url"),
            })
    return blocked


def evaluate_codeql_analyses(
    analyses: Iterable[dict[str, Any]],
    branch: str,
    max_age_days: int,
    now: datetime | None = None,
    expected_sha: str | None = None,
) -> dict[str, Any]:
    now = now or datetime.now(timezone.utc)
    expected_ref = f"refs/heads/{branch}"
    candidates = []
    for analysis in analyses:
        tool = analysis.get("tool") or {}
        if str(tool.get("name") or "").lower() != "codeql":
            continue
        if analysis.get("ref") != expected_ref:
            continue
        if str(analysis.get("error") or "").strip():
            continue
        if int(analysis.get("rules_count") or 0) <= 0:
            continue
        if expected_sha and str(analysis.get("commit_sha") or "").lower() != expected_sha:
            continue
        candidates.append(analysis)
    qualifier = f" at candidate {expected_sha}" if expected_sha else ""
    need(candidates, f"no successful CodeQL analysis with rules for {expected_ref}{qualifier}")
    latest = max(candidates, key=lambda x: parse_iso(x.get("created_at")))
    created = parse_iso(latest.get("created_at"))
    age_seconds = (now - created).total_seconds()
    need(age_seconds >= 0, "latest CodeQL analysis timestamp is in the future")
    age_days = age_seconds / 86400.0
    need(age_days <= max_age_days,
         f"latest CodeQL analysis is {age_days:.1f} days old; maximum accepted age is {max_age_days} days")
    return {
        "analysisId": latest.get("id"),
        "ref": latest.get("ref"),
        "commitSha": latest.get("commit_sha"),
        "createdAt": latest.get("created_at"),
        "rulesCount": int(latest.get("rules_count") or 0),
        "resultsCount": int(latest.get("results_count") or 0),
        "tool": latest.get("tool"),
        "ageDays": round(age_days, 3),
    }


def _find_names(names: Iterable[str], basename: str) -> list[str]:
    return [name for name in names if Path(name).name == basename]


def validate_supply_chain_artifact(blob: bytes, expected_sha: str | None = None) -> dict[str, Any]:
    need(len(blob) > 0, "supply-chain artifact ZIP is empty")
    try:
        zf = zipfile.ZipFile(io.BytesIO(blob))
    except zipfile.BadZipFile as exc:
        raise AcceptanceError("supply-chain artifact is not a valid ZIP") from exc
    with zf:
        names = [n for n in zf.namelist() if not n.endswith("/")]
        bom_json_names = _find_names(names, "bom.json")
        bom_xml_names = _find_names(names, "bom.xml")
        dep_json_names = [n for n in names if Path(n).name == "dependency-check-report.json"]
        dep_sarif_names = [n for n in names if Path(n).name == "dependency-check-report.sarif"]
        dep_html_names = [n for n in names if Path(n).name == "dependency-check-report.html"]
        codeql_gate_names = [n for n in names if n.replace("\\", "/").endswith("github-release-codeql-gate/github-connected-acceptance.json")]
        need(len(bom_json_names) == 1, f"expected exactly one bom.json, found {len(bom_json_names)}")
        need(len(bom_xml_names) == 1, f"expected exactly one bom.xml, found {len(bom_xml_names)}")
        need(dep_json_names, "missing dependency-check-report.json in supply-chain artifact")
        need(dep_sarif_names, "missing dependency-check-report.sarif in supply-chain artifact")
        need(dep_html_names, "missing dependency-check-report.html in supply-chain artifact")
        need(len(codeql_gate_names) == 1,
             f"expected exactly one candidate CodeQL release-gate JSON, found {len(codeql_gate_names)}")

        try:
            codeql_gate = json.loads(zf.read(codeql_gate_names[0]))
        except Exception as exc:  # noqa: BLE001
            raise AcceptanceError(f"invalid candidate CodeQL release-gate JSON: {exc}") from exc
        need(isinstance(codeql_gate, dict), "candidate CodeQL release-gate JSON root must be an object")
        need(codeql_gate.get("schemaVersion") == 2, "candidate CodeQL release gate must use schemaVersion 2")
        need(codeql_gate.get("scenario") == "github-connected-acceptance", "candidate CodeQL release gate scenario mismatch")
        need(codeql_gate.get("overall") == "PASS", "candidate CodeQL release gate is not PASS")
        gate_sha = normalize_sha(codeql_gate.get("candidateSha"), "candidate CodeQL release-gate SHA")
        if expected_sha:
            need(gate_sha == expected_sha, "candidate CodeQL release gate SHA does not match CI Release head SHA")
        gate_checks = codeql_gate.get("checks")
        need(isinstance(gate_checks, list) and len(gate_checks) == 1,
             "candidate CodeQL release gate must contain exactly one check")
        gate_row = gate_checks[0]
        need(isinstance(gate_row, dict) and gate_row.get("id") == "MHL-019-release-gate" and gate_row.get("status") == "PASS",
             "candidate CodeQL release gate check is missing or not PASS")

        try:
            bom_json = json.loads(zf.read(bom_json_names[0]))
        except Exception as exc:  # noqa: BLE001
            raise AcceptanceError(f"invalid CycloneDX JSON: {exc}") from exc
        need(str(bom_json.get("bomFormat") or "").lower() == "cyclonedx", "bom.json is not CycloneDX")
        need(str(bom_json.get("specVersion") or "") == "1.6", "bom.json must use CycloneDX 1.6")
        components = bom_json.get("components") or []
        need(isinstance(components, list) and components, "bom.json contains no components")

        try:
            xml_root = ET.fromstring(zf.read(bom_xml_names[0]))
        except Exception as exc:  # noqa: BLE001
            raise AcceptanceError(f"invalid CycloneDX XML: {exc}") from exc
        need(xml_root.tag.endswith("}bom") or xml_root.tag == "bom", "bom.xml root is not CycloneDX bom")
        xml_components = [el for el in xml_root.iter() if el.tag.endswith("}component") or el.tag == "component"]
        need(xml_components, "bom.xml contains no components")

        dependency_reports = []
        total_dependencies = 0
        total_vulnerabilities = 0
        for name in dep_json_names:
            try:
                report = json.loads(zf.read(name))
            except Exception as exc:  # noqa: BLE001
                raise AcceptanceError(f"invalid {name}: {exc}") from exc
            dependencies = report.get("dependencies")
            need(isinstance(dependencies, list), f"{name}: missing Dependency-Check dependencies array")
            vulnerability_count = sum(len(dep.get("vulnerabilities") or []) for dep in dependencies if isinstance(dep, dict))
            total_dependencies += len(dependencies)
            total_vulnerabilities += vulnerability_count
            dependency_reports.append({
                "path": name,
                "dependencies": len(dependencies),
                "vulnerabilities": vulnerability_count,
            })
        need(total_dependencies > 0, "Dependency-Check reports contain no dependencies")

        return {
            "fileCount": len(names),
            "bomJson": bom_json_names[0],
            "bomXml": bom_xml_names[0],
            "cycloneDxVersion": "1.6",
            "bomJsonComponents": len(components),
            "bomXmlComponents": len(xml_components),
            "dependencyCheckJsonReports": dependency_reports,
            "dependencyCheckSarifCount": len(dep_sarif_names),
            "dependencyCheckHtmlCount": len(dep_html_names),
            "dependencyCount": total_dependencies,
            "reportedVulnerabilityCount": total_vulnerabilities,
            "codeqlReleaseGate": codeql_gate_names[0],
            "codeqlReleaseGateCandidateSha": gate_sha,
        }


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_github_artifact_digest(blob: bytes, declared_digest: str, label: str) -> str:
    """Verify downloaded Actions artifact bytes against GitHub's declared SHA-256 digest."""
    digest = str(declared_digest or "").strip().lower()
    need(re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is not None,
         f"{label} lacks a valid GitHub sha256 artifact digest")
    actual = _sha256_bytes(blob)
    need(actual == digest.split(":", 1)[1],
         f"{label} downloaded ZIP SHA-256 does not match GitHub artifact digest")
    return actual


def validate_windows_release_artifact(blob: bytes, version: str, extract_dir: Path | None = None) -> dict[str, Any]:
    """Validate the Windows release artifact and return the exact MSI digest.

    The connected acceptance must bind later manual Windows evidence to the same
    release run/commit.  Therefore it validates the Windows artifact from that
    run and records the SHA-256 of the candidate MSI.
    """
    need(len(blob) > 0, "Windows release artifact ZIP is empty")
    try:
        zf = zipfile.ZipFile(io.BytesIO(blob))
    except zipfile.BadZipFile as exc:
        raise AcceptanceError("Windows release artifact is not a valid ZIP") from exc
    with zf:
        names = [n for n in zf.namelist() if not n.endswith("/")]
        need(names, "Windows release artifact contains no files")
        basenames: dict[str, list[str]] = {}
        for name in names:
            base = Path(name).name
            basenames.setdefault(base, []).append(name)
        duplicates = sorted(base for base, rows in basenames.items() if len(rows) > 1)
        need(not duplicates, "Windows release artifact has duplicate basenames: " + ", ".join(duplicates[:10]))

        msi_name = f"MyHomeLib-{version}.msi"
        exe_name = f"MyHomeLib-{version}.exe"
        sums_name = "SHA256SUMS"
        for required in (msi_name, exe_name, sums_name):
            need(required in basenames, f"Windows release artifact missing {required}")
        portable = [base for base in basenames if base.startswith(f"myhomelib-{version}-windows-") and base.endswith(".zip")]
        need(len(portable) == 1, f"Windows release artifact expected exactly one versioned portable ZIP, found {len(portable)}")

        sums_path = basenames[sums_name][0]
        try:
            sums_text = zf.read(sums_path).decode("utf-8-sig")
        except Exception as exc:  # noqa: BLE001
            raise AcceptanceError(f"cannot read Windows SHA256SUMS: {exc}") from exc
        entries: dict[str, str] = {}
        for line_no, raw in enumerate(sums_text.splitlines(), 1):
            if not raw.strip():
                continue
            parts = raw.strip().split(None, 1)
            need(len(parts) == 2, f"Windows SHA256SUMS line {line_no} is invalid")
            digest, rel = parts
            rel = rel.lstrip("*").replace("\\", "/")
            need(re.fullmatch(r"[0-9a-fA-F]{64}", digest) is not None, f"Windows SHA256SUMS line {line_no} has invalid digest")
            base = Path(rel).name
            need(base not in entries, f"Windows SHA256SUMS has duplicate basename entry: {base}")
            entries[base] = digest.lower()
        for required in (msi_name, exe_name, portable[0]):
            need(required in entries, f"Windows SHA256SUMS missing entry for {required}")
            artifact_path = basenames[required][0]
            actual = _sha256_bytes(zf.read(artifact_path))
            need(actual == entries[required], f"Windows artifact checksum mismatch for {required}")

        msi_path = basenames[msi_name][0]
        exe_path = basenames[exe_name][0]
        portable_path = basenames[portable[0]][0]
        msi_bytes = zf.read(msi_path)
        exe_bytes = zf.read(exe_path)
        portable_bytes = zf.read(portable_path)
        need(len(msi_bytes) > 0, f"Windows MSI candidate is empty: {msi_name}")
        need(len(exe_bytes) > 0, f"Windows EXE candidate is empty: {exe_name}")
        need(len(portable_bytes) > 0, f"Windows portable candidate is empty: {portable[0]}")
        msi_sha = _sha256_bytes(msi_bytes)
        exe_sha = _sha256_bytes(exe_bytes)
        portable_sha = _sha256_bytes(portable_bytes)
        if extract_dir is not None:
            extract_dir.mkdir(parents=True, exist_ok=True)
            (extract_dir / msi_name).write_bytes(msi_bytes)
            (extract_dir / exe_name).write_bytes(exe_bytes)
            (extract_dir / portable[0]).write_bytes(portable_bytes)
            (extract_dir / "candidate-windows.sha256").write_text(
                f"{msi_sha}  {msi_name}\n{exe_sha}  {exe_name}\n{portable_sha}  {portable[0]}\n",
                encoding="utf-8",
            )
        return {
            "windowsArtifactFileCount": len(names),
            "windowsMsiPath": msi_path,
            "windowsMsiSha256": msi_sha,
            "windowsExePath": exe_path,
            "windowsExeSha256": exe_sha,
            "windowsPortablePath": portable_path,
            "windowsPortableSha256": portable_sha,
            "windowsChecksumsPath": sums_path,
        }


def project_version() -> str:
    pom = ROOT / "pom.xml"
    try:
        root = ET.parse(pom).getroot()
    except Exception as exc:  # noqa: BLE001
        raise AcceptanceError(f"cannot read project version from {pom}: {exc}") from exc
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    value = root.findtext("m:version", namespaces=ns)
    need(bool(value and value.strip()), "pom.xml does not expose a project version")
    return str(value).strip()


def normalize_sha(value: str | None, label: str = "SHA") -> str | None:
    if value is None or not str(value).strip():
        return None
    normalized = str(value).strip().lower()
    need(re.fullmatch(r"[0-9a-f]{40}", normalized) is not None, f"{label} must be a full 40-character Git commit SHA")
    return normalized


class GitHubClient:
    def __init__(self, repo: str, token: str | None, api_url: str) -> None:
        need("/" in repo and not repo.startswith("/") and not repo.endswith("/"), "--repo must be OWNER/REPO")
        self.repo = repo
        self.token = token
        self.api_url = api_url.rstrip("/")

    def _headers(self) -> dict[str, str]:
        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": API_VERSION,
            "User-Agent": "myhomelib-connected-acceptance",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    def request(self, path_or_url: str) -> tuple[bytes, dict[str, str]]:
        url = path_or_url if path_or_url.startswith("http://") or path_or_url.startswith("https://") else f"{self.api_url}{path_or_url}"
        req = urllib.request.Request(url, headers=self._headers(), method="GET")
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                return response.read(), dict(response.headers.items())
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")[:1000]
            raise AcceptanceError(f"GitHub API {exc.code} for {url}: {body}") from exc
        except urllib.error.URLError as exc:
            raise AcceptanceError(f"GitHub API request failed for {url}: {exc}") from exc

    def download_artifact(self, path_or_url: str) -> bytes:
        """Download an Actions artifact without forwarding the bearer token to blob storage."""
        url = path_or_url if path_or_url.startswith("http://") or path_or_url.startswith("https://") else f"{self.api_url}{path_or_url}"
        req = urllib.request.Request(url, headers=self._headers(), method="GET")

        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
                return None

        opener = urllib.request.build_opener(NoRedirect)
        location = None
        try:
            with opener.open(req, timeout=60) as response:
                if 300 <= response.status < 400:
                    location = response.headers.get("Location")
                else:
                    return response.read()
        except urllib.error.HTTPError as exc:
            if exc.code in (301, 302, 303, 307, 308):
                location = exc.headers.get("Location")
            else:
                body = exc.read().decode("utf-8", errors="replace")[:1000]
                raise AcceptanceError(f"GitHub artifact API {exc.code} for {url}: {body}") from exc
        except urllib.error.URLError as exc:
            raise AcceptanceError(f"GitHub artifact request failed for {url}: {exc}") from exc

        need(bool(location), "GitHub artifact download response did not provide a redirect location")
        # The redirect points at short-lived blob storage. Deliberately do NOT carry
        # Authorization or GitHub API headers to that different host.
        blob_req = urllib.request.Request(str(location), headers={"User-Agent": "myhomelib-connected-acceptance"}, method="GET")
        try:
            with urllib.request.urlopen(blob_req, timeout=120) as response:
                return response.read()
        except (urllib.error.HTTPError, urllib.error.URLError) as exc:
            raise AcceptanceError(f"artifact blob download failed: {exc}") from exc

    def json(self, path: str) -> Any:
        raw, _ = self.request(path)
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise AcceptanceError(f"GitHub API returned invalid JSON for {path}: {exc}") from exc

    def paged(self, path: str, list_key: str | None = None, max_pages: int = 10) -> list[Any]:
        separator = "&" if "?" in path else "?"
        base = f"{path}{separator}per_page=100"
        result: list[Any] = []
        for page in range(1, max_pages + 1):
            payload = self.json(f"{base}&page={page}")
            items = payload.get(list_key) if list_key else payload
            need(isinstance(items, list), f"unexpected paginated payload for {path}")
            result.extend(items)
            if len(items) < 100:
                break
        return result


def repo_path(repo: str, suffix: str) -> str:
    quoted_repo = "/".join(urllib.parse.quote(x, safe="") for x in repo.split("/"))
    return f"/repos/{quoted_repo}{suffix}"


def find_latest_release_run(client: GitHubClient, explicit_run_id: int | None) -> dict[str, Any]:
    if explicit_run_id:
        run = client.json(repo_path(client.repo, f"/actions/runs/{explicit_run_id}"))
        need(run.get("status") == "completed" and run.get("conclusion") == "success",
             f"release run {explicit_run_id} is not a successful completed run")
        path = str(run.get("path") or "")
        need(path.endswith("/.github/workflows/ci-release.yml") or path == ".github/workflows/ci-release.yml",
             f"run {explicit_run_id} is not ci-release.yml (path={path!r})")
        return run

    runs = client.paged(
        repo_path(client.repo, "/actions/workflows/ci-release.yml/runs?status=success"),
        "workflow_runs",
        max_pages=2,
    )
    successful = [r for r in runs if r.get("status") == "completed" and r.get("conclusion") == "success"]
    need(successful, "no successful completed CI Release run found")
    return successful[0]


def collect_pr_fast_gate_rows(client: GitHubClient, min_runs: int, max_runs: int) -> list[dict[str, Any]]:
    runs = client.paged(
        repo_path(client.repo, "/actions/workflows/ci-pr.yml/runs?event=pull_request&status=completed"),
        "workflow_runs",
        max_pages=2,
    )
    candidates = [r for r in runs if r.get("conclusion") == "success"][:max_runs]
    need(len(candidates) >= min_runs,
         f"need at least {min_runs} successful completed PR CI runs, found {len(candidates)}")
    jobs_by_run: list[tuple[int, Iterable[dict[str, Any]]]] = []
    for run in candidates:
        run_id = int(run["id"])
        payload = client.json(repo_path(client.repo, f"/actions/runs/{run_id}/jobs?filter=latest&per_page=100"))
        jobs = payload.get("jobs") or []
        jobs_by_run.append((run_id, jobs))
    return fast_gate_durations(jobs_by_run)


def check_required_fast_gate(client: GitHubClient, branch: str) -> Check:
    encoded_branch = urllib.parse.quote(branch, safe="")
    rules = client.paged(repo_path(client.repo, f"/rules/branches/{encoded_branch}"), max_pages=2)
    contexts = required_status_contexts_from_branch_rules(rules)
    source = "rules"
    if not has_fast_gate_context(contexts):
        # Legacy branch protection may still be in use. 404 is represented by our
        # fail-closed API error, so only try this fallback when the rules endpoint
        # itself succeeded but did not expose the required context.
        try:
            legacy = client.json(repo_path(client.repo, f"/branches/{encoded_branch}/protection/required_status_checks"))
            legacy_contexts = required_status_contexts_from_legacy(legacy)
        except AcceptanceError:
            legacy_contexts = []
        if has_fast_gate_context(legacy_contexts):
            contexts = legacy_contexts
            source = "legacy-branch-protection"
    need(has_fast_gate_context(contexts),
         f"default branch {branch!r} does not require the 'Fast gate' status check")
    return Check("MHL-010-A", "PASS", "Default branch requires Fast gate", {"branch": branch, "source": source, "contexts": contexts})


def check_fast_gate_runtime(client: GitHubClient, min_runs: int, max_runs: int, max_median_seconds: float) -> Check:
    rows = collect_pr_fast_gate_rows(client, min_runs, max_runs)
    median = evaluate_fast_gate_runtime(rows, min_runs, max_median_seconds)
    return Check(
        "MHL-010-B", "PASS", "PR Fast gate median meets target",
        {"sampleCount": len(rows), "minimumRequired": min_runs, "medianSeconds": round(median, 3),
         "limitSeconds": max_median_seconds, "samples": rows},
    )


def check_supply_chain_run(
    client: GitHubClient, explicit_run_id: int | None, expected_sha: str | None, version: str,
    candidate_dir: Path | None = None,
) -> Check:
    run = find_latest_release_run(client, explicit_run_id)
    run_id = int(run["id"])
    run_sha = normalize_sha(str(run.get("head_sha") or ""), "CI Release head_sha")
    if expected_sha:
        need(run_sha == expected_sha,
             f"CI Release run {run_id} head SHA {run_sha} does not match acceptance candidate {expected_sha}")

    payload = client.json(repo_path(client.repo, f"/actions/runs/{run_id}/artifacts?per_page=100"))
    artifacts = payload.get("artifacts") or []

    supply = [a for a in artifacts if a.get("name") == "myhomelib-supply-chain" and not a.get("expired")]
    need(len(supply) == 1,
         f"CI Release run {run_id}: expected one non-expired myhomelib-supply-chain artifact, found {len(supply)}")
    artifact = supply[0]
    need(int(artifact.get("size_in_bytes") or 0) > 0, f"CI Release run {run_id}: supply-chain artifact is empty")
    digest = str(artifact.get("digest") or "")
    download_url = artifact.get("archive_download_url") or repo_path(client.repo, f"/actions/artifacts/{artifact['id']}/zip")
    blob = client.download_artifact(str(download_url))
    supply_download_sha = verify_github_artifact_digest(
        blob, digest, f"CI Release run {run_id} supply-chain artifact"
    )
    validated = validate_supply_chain_artifact(blob, run_sha)

    windows = [a for a in artifacts if a.get("name") == "myhomelib-windows" and not a.get("expired")]
    need(len(windows) == 1,
         f"CI Release run {run_id}: expected one non-expired myhomelib-windows artifact, found {len(windows)}")
    windows_artifact = windows[0]
    need(int(windows_artifact.get("size_in_bytes") or 0) > 0, f"CI Release run {run_id}: Windows artifact is empty")
    windows_digest = str(windows_artifact.get("digest") or "")
    windows_url = windows_artifact.get("archive_download_url") or repo_path(client.repo, f"/actions/artifacts/{windows_artifact['id']}/zip")
    windows_blob = client.download_artifact(str(windows_url))
    windows_download_sha = verify_github_artifact_digest(
        windows_blob, windows_digest, f"CI Release run {run_id} Windows artifact"
    )
    windows_validated = validate_windows_release_artifact(windows_blob, version, candidate_dir)

    details = {
        "runId": run_id,
        "runNumber": run.get("run_number"),
        "runAttempt": run.get("run_attempt"),
        "headSha": run_sha,
        "htmlUrl": run.get("html_url"),
        "createdAt": run.get("created_at"),
        "updatedAt": run.get("updated_at"),
        "artifactId": artifact.get("id"),
        "artifactDigest": digest,
        "artifactDownloadedZipSha256": supply_download_sha,
        "artifactSizeBytes": artifact.get("size_in_bytes"),
        "windowsArtifactId": windows_artifact.get("id"),
        "windowsArtifactDigest": windows_digest,
        "windowsArtifactDownloadedZipSha256": windows_download_sha,
        "windowsArtifactSizeBytes": windows_artifact.get("size_in_bytes"),
        **validated,
        **windows_validated,
    }
    return Check("MHL-017/MHL-018", "PASS", "Connected release produced bound SBOM, SCA and Windows candidate evidence", details)


def check_codeql(client: GitHubClient, branch: str, max_age_days: int, expected_sha: str | None = None) -> Check:
    ref = urllib.parse.quote(f"refs/heads/{branch}", safe="")
    analyses = client.paged(repo_path(client.repo, f"/code-scanning/analyses?ref={ref}"), max_pages=2)
    analysis = evaluate_codeql_analyses(analyses, branch, max_age_days, expected_sha=expected_sha)
    alerts = client.paged(repo_path(client.repo, "/code-scanning/alerts?state=open"), max_pages=10)
    blocked = blocking_code_scanning_alerts(alerts)
    need(not blocked,
         "open High/Critical code-scanning alerts block release: " + ", ".join(
             f"#{x.get('number')} {x.get('ruleId')} ({x.get('severity')})" for x in blocked[:10]
         ))
    return Check(
        "MHL-019", "PASS", "Recent CodeQL analysis exists and release-blocking alert set is clear",
        {"branch": branch, "analysis": analysis, "openAlertCount": len(alerts), "blockingAlertCount": 0},
    )


def codeql_release_gate(
    client: GitHubClient, branch: str, max_age_days: int, expected_sha: str
) -> Check:
    """Fail closed unless CodeQL has successfully analyzed this exact release candidate."""
    ref = urllib.parse.quote(f"refs/heads/{branch}", safe="")
    analyses = client.paged(repo_path(client.repo, f"/code-scanning/analyses?ref={ref}"), max_pages=2)
    analysis = evaluate_codeql_analyses(analyses, branch, max_age_days, expected_sha=expected_sha)
    alerts = client.paged(repo_path(client.repo, "/code-scanning/alerts?state=open"), max_pages=10)
    blocked = blocking_code_scanning_alerts(alerts)
    need(not blocked,
         "release blocked by open High/Critical code-scanning alerts: " + ", ".join(
             f"#{x.get('number')} {x.get('ruleId')} ({x.get('severity')})" for x in blocked[:10]
         ))
    return Check(
        "MHL-019-release-gate", "PASS",
        "Exact release candidate has successful CodeQL analysis and no open High/Critical alerts",
        {
            "branch": branch, "candidateSha": expected_sha, "analysis": analysis,
            "openAlertCount": len(alerts), "blockingAlertCount": 0,
        },
    )


def write_evidence(
    out_dir: Path, repo: str, branch: str | None, checks: list[Check], overall: str, candidate_sha: str | None = None,
    harness_manifest_sha256: str | None = None,
) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 2,
        "scenario": "github-connected-acceptance",
        "candidateSha": candidate_sha,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "githubApiVersion": API_VERSION,
        "repository": repo,
        "branch": branch,
        "overall": overall,
        "acceptanceHarnessManifestSha256": harness_manifest_sha256,
        "checks": [asdict(c) for c in checks],
    }
    json_path = out_dir / "github-connected-acceptance.json"
    json_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    lines = [
        "# GitHub connected acceptance evidence",
        "",
        f"- Repository: `{repo}`",
        f"- Branch: `{branch or 'n/a'}`",
        f"- GitHub REST API version: `{API_VERSION}`",
        f"- Candidate SHA: `{candidate_sha or 'n/a'}`",
        f"- Acceptance harness manifest SHA-256: `{harness_manifest_sha256 or 'n/a'}`",
        f"- Overall: **{overall}**",
        "",
        "| Check | Status | Summary |",
        "|---|---|---|",
    ]
    for check in checks:
        lines.append(f"| {check.id} | {check.status} | {check.summary} |")
    lines.extend(["", "## Machine-readable details", "", "See `github-connected-acceptance.json` in the same evidence directory.", ""])
    (out_dir / "github-connected-acceptance.md").write_text("\n".join(lines), encoding="utf-8")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.getenv("GITHUB_REPOSITORY"), help="OWNER/REPO (defaults to GITHUB_REPOSITORY)")
    parser.add_argument("--branch", help="default branch to accept; auto-detected when omitted")
    parser.add_argument("--token", default=os.getenv("GITHUB_TOKEN") or os.getenv("GH_TOKEN"))
    parser.add_argument("--api-url", default=os.getenv("GITHUB_API_URL", "https://api.github.com"))
    parser.add_argument("--min-pr-runs", type=int, default=5)
    parser.add_argument("--max-pr-runs", type=int, default=10)
    parser.add_argument("--max-fast-gate-median-seconds", type=float, default=600.0)
    parser.add_argument("--max-codeql-age-days", type=int, default=14)
    parser.add_argument("--release-run-id", type=int)
    parser.add_argument("--expected-sha", default=os.getenv("GITHUB_SHA"), help="full candidate commit SHA; defaults to GITHUB_SHA")
    parser.add_argument("--out-dir", type=Path, default=Path("target/github-connected-acceptance"))
    parser.add_argument("--codeql-release-gate-only", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    need(bool(args.repo), "repository is required via --repo or GITHUB_REPOSITORY")
    need(args.min_pr_runs >= 3, "--min-pr-runs must be at least 3")
    need(args.max_pr_runs >= args.min_pr_runs, "--max-pr-runs must be >= --min-pr-runs")
    client = GitHubClient(args.repo, args.token, args.api_url)
    expected_sha = normalize_sha(args.expected_sha, "--expected-sha")
    version = project_version()

    checks: list[Check] = []
    branch: str | None = args.branch
    try:
        if not branch:
            metadata = client.json(repo_path(args.repo, ""))
            branch = str(metadata.get("default_branch") or "")
            need(bool(branch), "GitHub repository metadata does not expose default_branch")

        if args.codeql_release_gate_only:
            need(expected_sha is not None, "CodeQL release gate requires --expected-sha/GITHUB_SHA")
            checks.append(codeql_release_gate(client, branch, args.max_codeql_age_days, expected_sha))
            write_evidence(args.out_dir, args.repo, branch, checks, "PASS", expected_sha)
            print("GitHub CodeQL release gate: PASS")
            return 0

        checks.append(check_required_fast_gate(client, branch))
        checks.append(check_fast_gate_runtime(client, args.min_pr_runs, args.max_pr_runs, args.max_fast_gate_median_seconds))
        checks.append(check_supply_chain_run(client, args.release_run_id, expected_sha, version, args.out_dir / "candidate-windows"))
        checks.append(check_codeql(client, branch, args.max_codeql_age_days, expected_sha))
        harness_mod = load_harness_binding_module()
        harness_manifest = args.out_dir / "acceptance-harness.sha256"
        harness_manifest_sha = harness_mod.write_manifest(harness_manifest, ROOT)
        write_evidence(args.out_dir, args.repo, branch, checks, "PASS", expected_sha, harness_manifest_sha)
        print("GitHub connected acceptance: PASS")
        for check in checks:
            print(f"- {check.id}: PASS — {check.summary}")
        return 0
    except AcceptanceError as exc:
        checks.append(Check("acceptance", "FAIL", str(exc), {}))
        try:
            write_evidence(args.out_dir, args.repo or "unknown", branch, checks, "FAIL", expected_sha)
        except Exception:
            pass
        print(f"GitHub connected acceptance: FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
