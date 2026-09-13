# Iteration 66 — Send to Device completion safety (MHL-505)

Date: 2026-09-13

## Scope

MHL-505 closes the removable-device completion gap without creating a second export pipeline. Existing `ExportToDeviceUseCase` behavior for batch processing, progress, cancellation, collision policy, crash-safe staging and device-profile artifact selection remains authoritative.

## Delivered

- `ExportRequest.CompletionPolicy` with backward-compatible `VERIFY_READABLE` and removable-device `EJECT_SAFE` modes.
- `ExportCompletionService`: required `FileChannel.force(true)` on the committed target file before success; parent-directory metadata flush best-effort for portability.
- Desktop Send to Device uses `EJECT_SAFE`.
- Successful completion explicitly tells the user to invoke the OS safe-removal/eject action before physical disconnect.
- Required durability-flush failure is reported as export failure and does not increment the exported-success count.
- Regression coverage preserves rename-without-overwrite default, ASK/skip, cancellation consistency and staging-file cleanup.

## Validation evidence

- targeted MHL-504/MHL-505 export regression: 14 tests, 0 failures/errors/skips
- full `myhomelib-application` suite: 255 tests, 0 failures/errors, 1 skipped
- `LayerArchitectureTest`: 14/14 PASS
- `tools/architecture-check.py`: PASS
- `tools/implementation-completeness-check.py`: PASS
- `tools/check-critical-ui-localization.py`: PASS (433 stable keys, 14 source files)
- `tools/static_release_check.py`: PASS (45 XML/FXML, 60 SQLite migrations, 1,407 production Java sources, 339 test sources)
- full 16-project offline `test-compile`: BUILD SUCCESS
- clean-source `build-check-v7.py`: PASS after relocating the pre-existing Iteration 64/65 changed-file markers from repository root to `docs/history/records/` as required by the documented history invariant

## Supplied real-data probes

- Two supplied FB2 ZIP archives: opt-in `RealFb2ReaderProbeTest` PASS for both books.
- Supplied `flibusta_online_fb2.inpx`: precheck counted 707,154 records; SHA-256 `75bebb7a7ccf203bd934ef2af986f17d737ba4c4abfc277956f60bb84a6c7655`; production import smoke reached 200,000 processed records with 0 parser/import errors before the execution tool limit. This is **not** a full-corpus completion or performance claim.

## Boundary

`EJECT_SAFE` means the application has forced the committed file through the filesystem before reporting success. It does not replace the operating system’s device-eject/safe-removal function and must not be described as physical unplug safety.

Next planned item: **MHL-506 — BookConverter SPI/capabilities**.
