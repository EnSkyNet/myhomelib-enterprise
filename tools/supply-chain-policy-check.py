#!/usr/bin/env python3
"""Offline structural gate for SBOM/SCA/CodeQL/source-release supply-chain policy."""
from __future__ import annotations

import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SUP_NS = "{https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd}"


def need(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def check_pom() -> None:
    root = ET.parse(ROOT / "pom.xml").getroot()
    profiles = {p.findtext("m:id", namespaces=NS): p for p in root.findall("m:profiles/m:profile", NS)}
    need("sbom" in profiles, "missing Maven sbom profile")
    need("dependency-check" in profiles, "missing Maven dependency-check profile")

    sbom = profiles["sbom"]
    plugin = sbom.find(".//m:plugin[m:artifactId='cyclonedx-maven-plugin']", NS)
    need(plugin is not None, "sbom profile missing cyclonedx-maven-plugin")
    need(plugin.findtext(".//m:goal", namespaces=NS) == "makeAggregateBom", "SBOM must be reactor aggregate")
    need(plugin.findtext(".//m:outputFormat", namespaces=NS) == "all", "SBOM must emit JSON and XML")
    need(plugin.findtext(".//m:includeRuntimeScope", namespaces=NS) == "true", "SBOM must include runtime scope")
    need(plugin.findtext(".//m:includeTestScope", namespaces=NS) == "false", "release SBOM must exclude test-only dependencies")

    sca = profiles["dependency-check"]
    dcp = sca.find(".//m:plugin[m:artifactId='dependency-check-maven']", NS)
    need(dcp is not None, "dependency-check profile missing OWASP plugin")
    threshold = float(dcp.findtext(".//m:failBuildOnCVSS", namespaces=NS) or "99")
    need(threshold <= 7.0, f"dependency policy too weak: failBuildOnCVSS={threshold}")
    suppression = dcp.findtext(".//m:suppressionFile", namespaces=NS) or ""
    need("security/dependency-check-suppressions.xml" in suppression, "dependency scan must use reviewed suppression file")


def check_suppressions() -> None:
    path = ROOT / "security/dependency-check-suppressions.xml"
    root = ET.parse(path).getroot()
    now = datetime.now(timezone.utc)
    for suppress in root.findall(f"{SUP_NS}suppress"):
        until = (suppress.attrib.get("until") or "").strip()
        need(until, "every dependency suppression must have an expiry (`until`)")
        try:
            expiry = datetime.strptime(until, "%Y-%m-%dZ").replace(tzinfo=timezone.utc)
        except ValueError as exc:
            raise AssertionError(f"invalid suppression expiry {until!r}; expected YYYY-MM-DDZ") from exc
        need(expiry > now, f"expired dependency suppression: {until}")
        notes = (suppress.findtext(f"{SUP_NS}notes") or "").strip()
        need(len(notes) >= 20, "every dependency suppression needs a substantive rationale in <notes>")
        need(re.search(r"(?:https?://|#[0-9]+|MHL-[0-9]+)", notes) is not None,
             "suppression rationale must reference an issue/URL")
        need(suppress.find(f"{SUP_NS}cvssBelow") is None and suppress.find(f"{SUP_NS}cvssScore") is None,
             "blanket CVSS-based suppressions are forbidden; suppress a narrow dependency/CVE")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def check_workflows() -> None:
    pr = read(".github/workflows/ci-pr.yml")
    rel = read(".github/workflows/ci-release.yml")
    codeql = read(".github/workflows/codeql.yml")
    connected = read(".github/workflows/github-acceptance.yml")

    for text, label in ((pr, "PR"), (rel, "release")):
        need("dependency-check" in text and "verify" in text, f"{label} workflow must run dependency-check profile")
        need("dependency-check-report" in text, f"{label} workflow must retain dependency-check report")
    need("-Psbom" in rel, "release workflow must generate SBOM")
    need("bom.json" in rel and "bom.xml" in rel, "release workflow must publish JSON and XML SBOM")
    need("github/codeql-action/init@v4" in codeql, "CodeQL v4 init missing")
    need("github/codeql-action/analyze@v4" in codeql, "CodeQL v4 analyze missing")
    need("pull_request:" in codeql and "schedule:" in codeql, "CodeQL must run on PR and schedule")
    need("security-events: write" in codeql, "CodeQL workflow needs security-events: write")
    need("github-connected-acceptance.py" in rel and "--codeql-release-gate-only" in rel,
         "release preflight must use the tested connected CodeQL release gate")
    need('--expected-sha "$GITHUB_SHA"' in rel,
         "release CodeQL preflight must bind the SAST gate to the exact release candidate SHA")
    need("github-release-codeql-gate/**" in rel,
         "release supply-chain artifact must retain exact-candidate CodeQL gate evidence")
    need("github-connected-acceptance-test.py" in pr,
         "PR fast gate must regression-test the connected acceptance policy")
    need("github-acceptance-artifact-ingest-test.py" in pr,
         "PR fast gate must regression-test GitHub acceptance artifact digest/safe-ingest policy")
    need("windows-acceptance-harness-binding-test.py" in pr,
         "PR fast gate must regression-test exact-candidate Windows acceptance harness binding")
    need("v71-final-external-acceptance-check-test.py" in pr,
         "PR fast gate must regression-test the final external evidence aggregator")
    need("v71-final-evidence-bundle-check-test.py" in pr,
         "PR fast gate must regression-test the immutable final reviewer evidence bundle")
    need("workflow_dispatch:" in connected, "connected GitHub acceptance must be manually runnable")
    need("actions: read" in connected and "security-events: read" in connected,
         "connected GitHub acceptance needs Actions and code-scanning read permissions")
    need("github-connected-acceptance.py" in connected,
         "connected GitHub acceptance workflow must run the acceptance collector")
    need("--expected-sha" in connected and "CANDIDATE_SHA" in connected,
         "connected GitHub acceptance must bind evidence to the dispatched candidate commit")
    need("--expect-windows-msi" in rel and "--expect-windows-exe" in rel,
         "release workflow must require publishable Windows MSI and EXE candidates")
    ingest = read("tools/github-acceptance-artifact-ingest.py")
    need("remoteDigestVerified" in ingest and "verify_github_artifact_digest" in ingest,
         "final GitHub evidence ingest must verify the Actions API artifact digest")
    need("candidate-windows.sha256 must contain exactly MSI, EXE and portable entries" in ingest,
         "final GitHub evidence ingest must bind MSI/EXE/portable candidate set")
    need("acceptance-harness.sha256" in ingest and "acceptanceHarnessManifestSha256" in ingest,
         "final GitHub evidence ingest must bind the Windows acceptance harness manifest")
    harness_binding = read("tools/windows-acceptance-harness-binding.py")
    need('"tools/windows-acceptance-host.ps1"' in harness_binding,
         "candidate-bound Windows acceptance harness manifest must include the shared host/session identity helper")
    finalizer = read("tools/v71-finalize-external-acceptance.ps1")
    need("--require-host-binding" in finalizer and "windows-host-binding.json" in finalizer,
         "final Windows evidence must require and retain one host/user/session binding")


def check_source_release_contract() -> None:
    mvnw = ROOT / "mvnw"
    mvnw_cmd = ROOT / "mvnw.cmd"
    wrapper_jar = ROOT / ".mvn/wrapper/maven-wrapper.jar"
    wrapper_props = ROOT / ".mvn/wrapper/maven-wrapper.properties"
    for path in (mvnw, mvnw_cmd, wrapper_jar, wrapper_props):
        need(path.is_file(), f"self-contained source launcher file missing: {path.relative_to(ROOT)}")
    with zipfile.ZipFile(wrapper_jar) as zf:
        need("org/apache/maven/wrapper/MavenWrapperMain.class" in zf.namelist(), "invalid Maven wrapper JAR")
    props = wrapper_props.read_text(encoding="utf-8")
    need("distributionUrl=" in props, "Maven wrapper properties missing distributionUrl")
    checksum_file = ROOT / ".mvn/wrapper/maven-wrapper.jar.sha256"
    need(checksum_file.is_file(), "bundled Maven wrapper JAR checksum file missing")
    expected = checksum_file.read_text(encoding="ascii").split()[0].lower()
    import hashlib
    actual = hashlib.sha256(wrapper_jar.read_bytes()).hexdigest()
    need(expected == actual, "bundled Maven wrapper JAR checksum mismatch")

    packager = read("tools/package-v71-source.py")
    need("verify_source_launcher" in packager, "source packager must verify wrapper contract after extraction")
    embedded = ROOT / ".mvn/maven/apache-maven-3.9.6/bin/mvn"
    need(embedded.is_file(), "source tree must contain embedded Maven 3.9.6 for self-contained launcher")
    need("Apache Maven 3.9.6" in packager, "source packager must verify embedded Maven version after extraction")


def main() -> int:
    check_pom()
    check_suppressions()
    check_workflows()
    check_source_release_contract()
    print("Supply-chain policy check: PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"Supply-chain policy check: FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
