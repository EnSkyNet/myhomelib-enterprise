#!/usr/bin/env python3
"""Central schema/version registry for external release evidence records.

The registry is deliberately strict. Evidence is release/security material, so a
reader must not silently accept an older or future schema. A schema bump requires
an explicit registry change plus producer/verifier regression updates.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping


class EvidenceContractError(ValueError):
    pass


@dataclass(frozen=True)
class EvidenceContract:
    scenario: str
    current_version: int
    accepted_versions: frozenset[int]
    required_fields: frozenset[str]


_BASE = frozenset({"schemaVersion", "scenario", "overall"})


def _contract(scenario: str, version: int, *required: str) -> EvidenceContract:
    return EvidenceContract(
        scenario=scenario,
        current_version=version,
        accepted_versions=frozenset({version}),
        required_fields=_BASE | frozenset(required),
    )


CONTRACTS: dict[str, EvidenceContract] = {
    "release-candidate-integrity": _contract(
        "release-candidate-integrity", 1,
        "projectVersion", "candidateSha", "platform", "sourceFileCount", "sourceTreeSha256",
        "criticalPolicyFiles", "distFileCount", "distManifestSha256", "sha256sSha256", "distFiles",
    ),
    "github-connected-acceptance": _contract(
        "github-connected-acceptance", 2,
        "candidateSha", "timestamp", "githubApiVersion", "repository", "branch",
        "acceptanceHarnessManifestSha256", "checks",
    ),
    "github-connected-acceptance-artifact-ingest": _contract(
        "github-connected-acceptance-artifact-ingest", 1,
        "candidateSha", "repository", "releaseRunId", "releaseRunUrl", "artifactZipSha256",
        "remoteDigestVerified", "windowsMsiSha256", "windowsExeSha256", "windowsPortableSha256",
        "windowsChecksumsSha256", "windowsIntegrityCandidateSha", "windowsIntegritySha256",
        "acceptanceHarnessManifestSha256",
    ),
    "windows-acceptance-harness-binding": _contract(
        "windows-acceptance-harness-binding", 1,
        "candidateSha", "manifestSha256", "fileCount", "files",
    ),
    "windows-acceptance-host-binding": _contract(
        "windows-acceptance-host-binding", 1,
        "timestamp", "acceptanceSessionId", "candidateSha", "repository", "acceptanceRunId",
        "host", "user", "os", "osVersion", "osBuild", "osArchitecture",
        "hostFingerprintSha256", "userFingerprintSha256", "isAdministrator",
    ),
    "windows-installer-lifecycle": _contract("windows-installer-lifecycle", 1),
    "windows-portable-unicode-smoke": _contract("windows-portable-unicode-smoke", 1),
    "windows-release-desktop-acceptance": _contract("windows-release-desktop-acceptance", 1),
    "windows-ui-dpi-acceptance": _contract("windows-ui-dpi-acceptance", 1),
    "myhomelib-7.1-final-external-acceptance": _contract(
        "myhomelib-7.1-final-external-acceptance", 1,
        "timestamp", "backlogItems", "evidence", "failure",
    ),
    "external-acceptance-readiness": _contract(
        "external-acceptance-readiness", 1,
        "timestamp", "releaseVersion", "externalGates", "regressionsRun", "checks", "closureRule",
    ),
}


def contract_for(scenario: str) -> EvidenceContract:
    try:
        return CONTRACTS[scenario]
    except KeyError as exc:
        raise EvidenceContractError(f"unsupported evidence scenario: {scenario!r}") from exc


def current_schema(scenario: str) -> int:
    return contract_for(scenario).current_version


def validate_record(record: Mapping[str, Any], scenario: str, *, label: str = "evidence record") -> int:
    if not isinstance(record, Mapping):
        raise EvidenceContractError(f"{label}: JSON root must be an object")
    contract = contract_for(scenario)
    actual_scenario = record.get("scenario")
    if actual_scenario != scenario:
        raise EvidenceContractError(
            f"{label}: scenario={actual_scenario!r}, expected {scenario!r}"
        )
    version = record.get("schemaVersion")
    if isinstance(version, bool) or not isinstance(version, int):
        raise EvidenceContractError(f"{label}: schemaVersion must be an integer")
    if version not in contract.accepted_versions:
        accepted_values = sorted(contract.accepted_versions)
        accepted = ", ".join(str(v) for v in accepted_values)
        if len(accepted_values) == 1:
            raise EvidenceContractError(
                f"{label}: schemaVersion must be {accepted_values[0]}; found {version} (unsupported)"
            )
        raise EvidenceContractError(
            f"{label}: unsupported schemaVersion={version}; accepted version(s): {accepted}"
        )
    missing = sorted(field for field in contract.required_fields if field not in record)
    if missing:
        raise EvidenceContractError(f"{label}: missing required field(s): {', '.join(missing)}")
    return version
