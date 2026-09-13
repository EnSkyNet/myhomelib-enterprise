#!/usr/bin/env python3
from __future__ import annotations

from copy import deepcopy

from evidence_contracts import CONTRACTS, EvidenceContractError, current_schema, validate_record


def fail(record, scenario: str, needle: str) -> None:
    try:
        validate_record(record, scenario, label="fixture")
    except EvidenceContractError as exc:
        assert needle.lower() in str(exc).lower(), (needle, str(exc))
    else:
        raise AssertionError(f"expected failure containing {needle!r}")


def fixture(scenario: str) -> dict[str, object]:
    contract = CONTRACTS[scenario]
    data: dict[str, object] = {field: "x" for field in contract.required_fields}
    data["schemaVersion"] = contract.current_version
    data["scenario"] = scenario
    data["overall"] = "PASS"
    return data


def main() -> int:
    assert len(CONTRACTS) >= 10
    for scenario, contract in CONTRACTS.items():
        record = fixture(scenario)
        assert current_schema(scenario) == contract.current_version
        assert validate_record(record, scenario, label=scenario) == contract.current_version

        missing = deepcopy(record)
        missing.pop("schemaVersion")
        fail(missing, scenario, "schemaversion")

        legacy = deepcopy(record)
        legacy["schemaVersion"] = contract.current_version - 1
        fail(legacy, scenario, "unsupported")

        future = deepcopy(record)
        future["schemaVersion"] = contract.current_version + 1
        fail(future, scenario, "unsupported")

        boolean = deepcopy(record)
        boolean["schemaVersion"] = True
        fail(boolean, scenario, "must be an integer")

        wrong = deepcopy(record)
        wrong["scenario"] = "wrong-scenario"
        fail(wrong, scenario, "expected")

        required = next((f for f in contract.required_fields if f not in {"schemaVersion", "scenario", "overall"}), None)
        if required:
            incomplete = deepcopy(record)
            incomplete.pop(required)
            fail(incomplete, scenario, "missing required field")

    try:
        current_schema("not-registered")
    except EvidenceContractError as exc:
        assert "unsupported evidence scenario" in str(exc)
    else:
        raise AssertionError("unknown evidence scenario was accepted")

    print(f"evidence contract registry regression tests: PASS ({len(CONTRACTS)} scenarios)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
