# Iteration 73 — External acceptance handoff readiness

Date: 2026-09-13

## Scope

Release-handoff hardening only. No production Java behavior changes and no external MHL gate closure.

## Changes

- refreshed `docs/release/CURRENT-VALIDATION.md` from the stale Iteration 60 record to the Iteration 72 regression baseline;
- added `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md` as the canonical six-gate operator sequence;
- added `tools/external-acceptance-readiness.py` and its regression test;
- added the readiness ratchet to PR CI;
- cleaned the README so only one current source checkpoint is advertised;
- kept the formal Maven release identity at `7.1.0`; compatibility-stable `v71-*` finalizer entrypoints remain valid while all evidence is candidate-SHA/artifact-hash bound.

## Validation contract

A successful readiness run is **not** external acceptance. It must report:

- `overall = READY_FOR_LIVE_EVIDENCE`;
- MHL-010/011/012/017/018/019 = `OPEN_EXTERNAL`;
- the exact Maven release identity read from `pom.xml`;
- a deterministic fingerprint of all critical Windows acceptance harness files;
- successful offline evidence-policy regressions when `--run-regressions` is requested.

Real GitHub/Windows evidence remains mandatory for external closure.
