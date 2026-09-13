# Iteration 72 — 8.0 release hardening / regression consolidation

Date: 2026-09-13
Base: Iteration 71 / MHL-501…510 locally complete
Status: DONE (local hardening; external gates unchanged)

## Scope

No production Java behavior is added. This checkpoint repairs validation debt exposed by running the finished 8.0 source through broader regression coverage.

## Hardening changes

- Updated `ComicReaderWorkflowContractTest` to assert the already-present `!currentAudio` guard when refreshing text annotations.
- Updated `BackupRestoreJourneyE2ETest` to model the current V59+ `reading_progress.last_device` schema expected after Flyway migration.
- Restored deterministic Reader golden ZIP coverage with `reader-rich.zip` containing the existing rich FB2 plus a synthetic second FB2 fixture.
- Configured Reader Surefire with `java.awt.headless=true` so PDFBox rasterization tests run without X11; production runtime flags are unchanged.
- Corrected active plugin-development documentation from Plugin API 1.2 to 1.3.

## Regression evidence

- Shared: 15 tests, 0 failures/errors.
- Domain: 24 tests, 0 failures/errors.
- Application: 284 tests, 0 failures/errors, 1 skip.
- Plugin API: 28 tests, 0 failures/errors.
- Plugin samples: 2 tests, 0 failures/errors.
- Infrastructure: 132 test classes / 432 tests, 0 failures/errors, 7 skips (exhaustive split groups).
- Reader: 77 tests, 0 failures/errors, 1 skip.
- UI: 93 tests, 0 failures/errors.
- Bootstrap: 17 tests, 0 failures/errors.
- Architecture: 14 tests, 0 failures/errors.
- E2E: 14 tests, 0 failures/errors.
- Benchmark: 3 tests, 3 environment/profile skips.
- MCP: 6 tests, 0 failures/errors.
- Web: 6 tests, 0 failures/errors.
- OPDS: 20 tests, 0 failures/errors.
- Aggregate split regression: **1,035 tests, 0 failures, 0 errors, 12 skips**.
- Full 16-project offline `test-compile`: **BUILD SUCCESS**.
- `tools/architecture-check.py`: PASS.
- `tools/implementation-completeness-check.py`: PASS.
- `tools/check-critical-ui-localization.py`: PASS.
- `tools/static_release_check.py`: PASS.
- `tools/supply-chain-policy-check.py`: PASS.
- `LayerArchitectureTest`: 14/14 PASS.

## Monolithic reactor boundary

A root offline `mvn test` was attempted after Iteration 71. Shared, Domain, Application, Plugin API and plugin samples completed without failure, and Infrastructure began executing, but the foreground execution window ended before the full reactor completed. The run is therefore **not** classified as PASS. Iteration 72 uses the exhaustive split module/class runs above as its local regression baseline and does not supersede the last completed monolithic baseline with an unproven claim.

## External boundary

MHL-010/011/012/017/018/019 remain OPEN. They require real Windows/GitHub evidence and are not simulated or closed by this local hardening checkpoint.
