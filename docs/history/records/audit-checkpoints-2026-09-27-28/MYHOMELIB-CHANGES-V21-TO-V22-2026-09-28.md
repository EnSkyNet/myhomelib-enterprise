# MyHomeLib — Changes V21 → V22

Date: 2026-09-28  
Baseline: `myhomelib-enterprise-optimized-v21-checkpoint-2026-09-28.zip`.

## Why V22 exists

A real Windows log from V20 reproduced two conversion defects for the supplied large FB2 corpus:

- Calibre FB2 → EPUB hit the historical 300 s timeout;
- after timeout, Windows still reported `mhl-calibre-*/input.fb2` as locked during workspace cleanup.

The user also requested conversion of multiple checkbox-selected books instead of only the current row.

## Production changes

### Large Calibre jobs

- `CalibreCliBookConverter` default timeout: **300 s → 1800 s**.
- The exact historical value `300` is treated as a legacy default and upgraded to 1800 s; other explicit custom values remain respected.
- Timeout remains bounded to 10…3600 s.
- Calibre plugin default/runtime timeout is aligned to 1800 s with the same legacy-300 handling.
- Settings/runtime property defaults are aligned to 1800 s.
- `ProcessExecutionSupport` termination grace: **2 s → 10 s** to give forcibly terminated Windows descendants time to exit and release file handles before workspace deletion.

### Batch conversion

- Convert command now consumes canonical checkbox selection from `BookSelectionService`.
- Empty checkbox selection preserves existing behavior: convert current row only.
- Non-empty checkbox selection converts all selected books.
- Batch dialog offers only target formats available for every selected book.
- External conversion concurrency is intentionally bounded to **2** lanes.
- One Operation Center row represents the batch; Cancel prevents queued conversions from starting and propagates cancellation to active conversions.
- Completion reports success count; partial failures report success/failure counts.
- Added Ukrainian/English/Bulgarian localization for batch conversion UI.

## Tests / verification

- Added regression/characterization coverage for legacy 300 s timeout migration, process termination grace, checkbox selection propagation, common target format intersection, bounded batch concurrency and queued-work cancellation.
- Final full Maven 3.9.16 reactor on the V22 source state: **1815 tests, 0 failures, 0 errors, 17 skipped; BUILD SUCCESS; 20/20 modules SUCCESS**.
- Production package: PASS.
- Desktop app-image smoke: PASS.
- Portable package smoke: PASS.
- Runtime-tools smoke: PASS for available packaged tools.
- Strict Stage23 artifact validation: PASS.
- `SHA256SUMS`: **225 entries**.
- Architecture/static/supply-chain checks: PASS.

## Important limitation

The exact user failure is Windows-specific. This Linux environment cannot execute the supplied Windows `ebook-convert.exe`, so V22 still requires the real Windows acceptance described in `MYHOMELIB-NEXT-TASKS-V22-2026-09-28.md` before the large-file fix is considered fully production-accepted.
