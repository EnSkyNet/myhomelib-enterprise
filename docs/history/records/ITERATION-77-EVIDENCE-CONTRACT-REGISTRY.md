# Iteration 77 — Versioned external-evidence contract registry

Date: 2026-09-13

## Scope

Release/evidence hardening only. No production Java behavior change and no external MHL gate closure.

## Delivered

- Added `tools/evidence_contracts.py` as the authoritative registry for external-evidence `scenario` + `schemaVersion` contracts.
- Registered release-candidate integrity, GitHub connected acceptance, artifact ingest, Windows harness/host and Windows report scenarios, final external decision and readiness records.
- Producers use `current_schema(...)`; verifiers use `validate_record(...)` and fail closed on missing, non-integer, legacy, future or wrong-scenario evidence.
- Added `tools/evidence-contracts-test.py` covering every registered scenario and negative schema/version paths.
- Bound the registry into release-candidate critical-policy hashing and the Windows acceptance-harness fingerprint.
- External readiness now ratchets the registry structure and regression.

## Validation

- Evidence-contract registry regression: PASS (11 scenarios).
- Existing release-integrity / GitHub connected / ingest / Windows evidence / harness / final-decision / final reviewer regressions: PASS.
- External readiness: READY_FOR_LIVE_EVIDENCE, 25/25 checks, 9/9 evidence-policy regressions.
- External gates MHL-010/011/012/017/018/019 remain OPEN and require real GitHub/Windows evidence.
