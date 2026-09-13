# Iteration 49 checkpoint — Content extraction, content index, annotation search

**Date:** 2026-09-12  
**Local status:** DONE  
**Tasks:** MHL-301, MHL-302, MHL-306

## Closure summary

Iteration 49 establishes the 7.4 full-text-search foundation without coupling content parsing to Reader or catalogue metadata search. The application layer owns the format-neutral extraction/index contracts; infrastructure owns FB2/EPUB/TXT extraction, the physically separate Lucene content index and local annotation FTS5 implementation. Global Search now exposes annotation matches and routes result activation through the existing Reader annotation boundary.

## Defect found during closure

Adding migration V58 correctly advanced the SQLite schema, but `DatabaseMigrationMatrixTest` still asserted version 57. The matrix was updated to expect V58 and now also includes V57 as a source schema, explicitly exercising the new annotation-search migration from the immediately previous release state.

## MHL-302 benchmark

`ITERATION-49-CONTENT-INDEX-BENCHMARK.csv` records the opt-in 5,000-document run. The benchmark verifies that the separate catalogue-index sentinel remains byte-identical while the content index is rebuilt and queried.

## Verification

- Full offline reactor: **13/13 modules BUILD SUCCESS**.
- Aggregate Surefire: **852 tests; 0 failures; 0 errors; 11 skipped**.
- Iteration 49 source contract: **11/11 PASS**.
- Architecture baseline: **PASS**.
- Implementation completeness: **PASS**.
- Critical localization, supply-chain, XML/archive security, artifact-health and static-release gates: **PASS**.
- `git diff --check`: **PASS**.
- External acceptance MHL-010/011/012/017/018/019 remains OPEN and is not represented as locally closed.
