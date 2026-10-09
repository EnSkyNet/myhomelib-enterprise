# MyHomeLib — Next Tasks after V19

Date: 2026-09-28  
Use `myhomelib-enterprise-optimized-v19-checkpoint-2026-09-28.zip` as the only baseline for the next session.

## P0 — Windows x64 / real Calibre cancellation acceptance

The exact Maven 3.9.16 Linux/ASCII/non-ASCII gate is complete. Do not reopen the V19 Maven lifecycle pins unless a concrete regression proves a problem.

Validate V19 on Windows x64 with Calibre installed. Capture real process tree and logs before changing code for any failure.

Required acceptance:

- long FB2 → EPUB conversion remains responsive;
- unrelated navigation, Reader state and online download remain usable during conversion;
- Operation Center Cancel requests cancellation deterministically and only once;
- the operation remains non-terminal until worker/process cleanup finishes;
- terminal outcome is `CANCELLED`, not `FAILED`, and no conversion error dialog is shown;
- timeout/cancel terminates the complete Calibre child process tree;
- temporary `mhl-calibre-*` workspace is removable after termination;
- no stale `CONVERSION` operation lease remains;
- app shutdown during conversion leaves no locked `input.fb2` and no orphan process.

For a Windows-only failure: regression test first where deterministic modeling is possible, then the smallest production fix, then targeted + Windows acceptance rerun.

## P0 — formal release libmobi input

V19 makes the policy explicit: a **formal full desktop release requires verified bundled libmobi 0.12 / `mobitool` plus its license**.

Current source still lacks `tools/converters/libmobi/build-src`, so strict full-release packaging is intentionally fail-closed.

Next action is not to weaken the release script. Supply/restore the verified licensed libmobi release input, then run:

- runtime-tool preparation in strict mode;
- platform package;
- packaged runtime-tool smoke;
- portable smoke;
- `SHA256SUMS` generation;
- Stage23 with `--require-checksums --require-portable`;
- applicable Windows installer acceptance.

## P1 — next defect-first audit focus

After Windows acceptance, prioritize concrete correctness evidence in this order:

1. collection-switch barriers while long-running conversion/download/import work is completing;
2. Windows file-publication and replace/move races, especially destination replacement and cleanup after cancellation;
3. shutdown barriers for external-process operations;
4. stale Operation Center/cancellation-action cleanup under exceptional completion paths.

Do not refactor for class size alone.

## P2 — architecture debt

Triage the unchanged standalone architecture-ratchet group only after the P0/P1 correctness gates:

- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

For each proposed change: characterization → one responsibility boundary → targeted tests → full relevant module → architecture/static check. Do not game the ratchet metric.

## P2 — packaging/release follow-through

The packaging-policy decision itself is closed in V19:

- publishable release artifacts require `SHA256SUMS` and strict Stage23;
- intermediate/raw app-image validation may remain non-strict;
- formal full release requires bundled verified libmobi; configured/system `mobitool` is an intermediate/runtime fallback, not a substitute for the full-release input.

Remaining work is evidence/execution, not another policy rewrite.

## P3 — standalone maintenance tooling

`tools/dependency-upgrade-check.py` was repaired in V19 after a concrete failure. Do not launch a broad cleanup campaign across old audit tools. Run standalone tools when relevant to the active gate and repair only reproducible failures with a clear current purpose.

## P3 — performance/memory

Make no performance or memory change without a reproducible baseline and A/B measurement on the same workload/environment. Functional test counts, code-size reductions or Maven timing differences are not performance evidence.
