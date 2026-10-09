# MyHomeLib — Technical Audit Report V22

Date: 2026-09-28  
Baseline: V21 release-candidate source.

## Executive status

V22 is a focused conversion checkpoint created from real Windows evidence, not a broad refactor. It addresses the reproduced five-minute Calibre ceiling, improves post-termination handle-release tolerance, and adds checkbox-driven batch book conversion with bounded concurrency.

## Reproduced field evidence

For `Демон. Пожиратель душ #01-18`, the application successfully parsed the FB2 (7,756,433 text characters), but Calibre conversion later failed at exactly 300,000 ms. The same log shows a Windows `FileSystemException` deleting the temporary `input.fb2` because another process still held it after timeout.

This establishes two concrete defects: the historical default is too small for this workload, and timeout teardown needs more time for Windows descendant/process handle release.

## Fixes

1. Calibre default timeout is 1800 s, with exact legacy `300` migrated at runtime to 1800 s while preserving custom values.
2. Calibre plugin timeout policy is aligned with the infrastructure converter.
3. External-process termination grace is 10 s instead of 2 s.
4. Conversion command uses checkbox selection; no checkbox selection falls back to the current row.
5. Batch conversion is capped at two in-flight conversions and stops queued work after cancellation.
6. Batch target format is the intersection of formats available for every selected book.

## Verification

Final source state under Maven 3.9.16:

- **1815 tests**;
- **0 failures**;
- **0 errors**;
- **17 skipped**;
- **20/20 reactor modules SUCCESS**;
- production package / desktop smoke / portable smoke / Stage23: PASS;
- checksums: 225 entries;
- architecture/static/supply-chain: PASS.

The supplied large FB2 was also parsed by the real FB2 reader probe successfully in the audit environment. The Windows Calibre binary itself cannot be executed here, so real Windows conversion acceptance remains mandatory.

## Release decision

V22 is the recommended source baseline for the next session. Do not return to V20/V21 for conversion work. Do not change timeout/process lifecycle/batch semantics again unless the V22 Windows acceptance reproduces an incorrect observable result.
