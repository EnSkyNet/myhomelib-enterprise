# MyHomeLib — Changes V22 → V23

Date: 2026-09-29  
Baseline: `myhomelib-enterprise-optimized-v22-checkpoint-2026-09-28.zip` only.

## Scope

V23 contains release/CI/tooling hardening discovered by a clean re-audit of V22. **No production Java behavior was changed.** In particular, V22 Calibre timeout/process teardown/batch-conversion semantics remain intact.

## Changed files / structure

### Source-tree hygiene

- moved 39 historical root checkpoint Markdown files to `docs/history/records/audit-checkpoints-2026-09-27-28/`;
- refreshed `docs/release/CURRENT-VALIDATION.md` with exact-current 2026-09-29 evidence.

### Release / CI tooling

- `tools/v71-standalone-java-smoke.py`
  - current JDK-only filesystem/download dependencies added to the isolated production source-set;
  - narrow temporary ZIP API adapter supplied for the Commons Compress boundary;
  - real `DownloadPayloadValidator` and `ConnectionScriptExecutor` remain exercised;
  - local HTTP harness no longer violates the reusable-credential HTTPS policy.
- `tools/stage8-9-filter-table-check.py`
  - denormalized column ownership follows canonical `BookUpsertSql` instead of stale inline SQL text.
- `tools/architecture-check-regression-test.py`
  - expected UI-debt ratchet marker aligned with current architecture-check output.
- `tools/github-connected-acceptance-test.py`
  - removed fixed-date CodeQL freshness expiry from the high-level gate regression.
- `tools/external-acceptance-readiness.py`
  - current-validation structural contract aligned to the exact-current candidate evidence.
- `tools/package-v71-source.py`
  - `create_zip()` reapplies the canonical generated-file exclusion policy at archive time, preventing Python gates from leaking newly created `__pycache__`/other generated artifacts into the formal source ZIP.

## Verification

- targeted repaired gates: PASS;
- external acceptance readiness with all offline regressions: `READY_FOR_LIVE_EVIDENCE`;
- real supplied FB2 reader corpus: 3/3 archives PASS;
- full Maven 3.9.16 `clean verify`: **1815 tests, 0 failures, 0 errors, 17 skipped; 20/20 modules SUCCESS**;
- static/architecture/catalog/Stage 8+9/24/25C/supply-chain/YAML/shell gates: PASS.

## Intentionally unchanged

- production Java source behavior;
- Flyway migrations;
- Calibre 1800 s timeout and legacy-300 migration;
- process termination grace 10 s;
- batch conversion selection/common-format/concurrency/cancellation semantics;
- compatibility matrix for conversion vs reader/download;
- performance/memory algorithms.

No refactor or performance change was accepted without a concrete regression or same-environment A/B evidence.
