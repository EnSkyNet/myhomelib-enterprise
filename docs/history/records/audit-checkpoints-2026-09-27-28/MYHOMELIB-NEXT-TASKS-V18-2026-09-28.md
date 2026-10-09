# MyHomeLib — Next Tasks after V18

Date: 2026-09-28  
Use `myhomelib-enterprise-optimized-v18-checkpoint-2026-09-28.zip` as the only baseline for the next session.

## P0 — exact Maven 3.9.16 acceptance

The supplied offline Maven distribution is 3.9.6; the wrapper contract is 3.9.16.

When Maven 3.9.16 is available, run in a clean UTF-8 environment:

1. `./mvnw -v` and verify 3.9.16 exactly.
2. Full `clean verify` under Xvfb where required.
3. Compare aggregate results to V18 reference: **1793 tests, 0 failures/errors, 17 skipped**.
4. Production package with the production profile.
5. Linux `jpackage` app-image.
6. Packaged launcher release smoke.
7. Stage23 artifact validation.
8. Repeat from an ASCII checkout path and a non-ASCII checkout path.

Do not reopen completed V14–V17 large-class extractions unless this gate exposes a concrete regression.

## P0 — Windows x64 / real Calibre cancellation acceptance

Validate V18 on Windows x64 with Calibre installed. Capture the real process tree and logs before changing code for any failure.

Required acceptance:
- long FB2 → EPUB conversion remains responsive;
- unrelated navigation, reader state and online download remain usable during conversion;
- Operation Center Cancel requests cancellation deterministically and is one-shot;
- the operation remains non-terminal until worker/process cleanup finishes;
- terminal outcome is `CANCELLED`, not `FAILED`, and no conversion error dialog is shown;
- timeout/cancel terminates the complete Calibre child process tree;
- temporary `mhl-calibre-*` workspace is removable after termination;
- no stale `CONVERSION` operation lease remains;
- app shutdown during conversion leaves no locked `input.fb2` and no orphan process.

If a Windows-only failure is found: regression test first where it can be modeled deterministically, then the smallest production fix, then targeted + Windows acceptance rerun.

## P1 — next defect-first audit focus

After Windows acceptance, prioritize concrete correctness evidence in this order:

1. collection-switch barriers while long-running conversion/download/import work is completing;
2. Windows file-publication and replace/move races, especially destination replacement and cleanup after cancellation;
3. shutdown barriers for external-process operations;
4. stale Operation Center/cancellation-action cleanup under exceptional completion paths.

Do not refactor for class size alone.

## P2 — architecture debt

Triage the unchanged standalone architecture-ratchet group only after the P0/P1 gates:
- `DeferredMainFeatureActions`;
- `ReaderBookMapCoordinator`;
- `ReaderBookmarkCoordinator`.

For each proposed change: characterization → one responsibility boundary → targeted tests → full relevant module → architecture/static check. Do not game the ratchet metric.

## P2 — packaging completeness

Make explicit release decisions for:
- bundled libmobi/mobitool versus an explicitly external runtime dependency;
- whether production/release packaging must always create `SHA256SUMS` and Stage23 release validation should require it.

If checksum generation becomes mandatory, add a packaging regression/acceptance check before changing scripts; then make the smallest script/CI change and run Stage23 in strict checksum mode.

## P3 — performance/memory

Make no performance or memory change without a reproducible baseline and A/B measurement on the same workload/environment. Functional test counts or code-size reductions are not performance evidence.
