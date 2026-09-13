# Iteration 78 — Final local preflight / release-candidate preparation

Date: 2026-09-13

## Scope

Finish every validation step that can be executed locally without inventing GitHub or Windows evidence.

## Production hardening

`ContentIndexingQueueService` no longer uses `Executors.newFixedThreadPool`, whose backing queue is unbounded. The worker executor is now owned explicitly and uses a `SynchronousQueue` with `AbortPolicy`; the existing `active.size() < limit` dispatch cap remains the authoritative work bound.

## Executable regression evidence

- Complete split baseline: **1,035 tests; 0 failures; 0 errors; 12 skipped**.
- Shared 15/15; Domain 24/24; Application 284/0/0/1; Plugin API 28/28; plugin samples 2/2.
- Infrastructure **432/0/0/7** by exhaustive split execution, including watcher/monitor 7/7.
- Reader 77/0/0/1; UI 93/0/0/0; Bootstrap 17/17; Architecture 14/14; E2E 14/14; benchmark 3 environment skips; MCP 6/6; Web 6/6; OPDS 20/20.
- No new uninterrupted monolithic full-reactor PASS is claimed.

## Local release gates

- Locally runnable check sweep: **84 PASS**.
- External-input-only checks: exactly 3 (`windows-acceptance-evidence-check.py`, `v71-final-external-acceptance-check.py`, `v71-final-evidence-bundle-check.py`).
- Offline acceptance helper: PASS.
- External readiness: **25/25 checks + 9/9 regressions PASS**, `READY_FOR_LIVE_EVIDENCE`.
- Linux `-Pproduction` packaging build: PASS.
- Linux packaged launcher smoke: PASS.
- Extracted portable archive smoke: PASS.
- `SHA256SUMS` generation + Stage23 release-artifact validation: PASS.
- Final clean-source 16-project `test-compile`: required before packaging and recorded in the release handoff.

## External boundary

MHL-010/011/012/017/018/019 remain OPEN and require real GitHub/Windows evidence. Use `docs/release/EXTERNAL-TEST-PLAN-ITERATION-78.md` and the canonical external acceptance runbook.
