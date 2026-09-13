# Iteration 62 — Annotation FTS performance / stable-rowid hardening (MHL-306 follow-up)

Date: 2026-09-12
Status: DONE locally

## Problem

The V58 annotation FTS schema stores `annotation_id` as an `UNINDEXED` FTS5 column. Its maintenance triggers nevertheless used predicates such as `DELETE FROM annotation_search_fts WHERE annotation_id=?`. With thousands of annotations, every insert/update could scan the FTS table, so bulk annotation creation degraded toward quadratic work.

## Scope

- Kept historical V58 unchanged and added Flyway V60.
- Added `annotation_search_ids`, mapping each annotation id to a stable integer `fts_rowid`.
- Rebuilt `annotation_search_fts` once during V60 so every indexed row receives that stable `rowid`.
- Replaced annotation/anchor/tag/book-title FTS maintenance with rowid-addressed delete/refresh operations.
- Added explicit cleanup of the rowid mapping when an annotation is deleted.
- Used an explicit `INTEGER PRIMARY KEY AUTOINCREMENT` mapping key rather than the physical `annotations.rowid`; live keys therefore remain stable across SQLite `VACUUM`.
- Extended the migration matrix through V59 → V60 and preserved indexed annotation content during the upgrade.
- Added regression coverage for post-`VACUUM` refreshes and FTS duplicate prevention.

## Acceptance evidence

- Targeted annotation/migration/backup acceptance: **8/8 PASS**.
- Infrastructure suite coverage: **130/130 test classes**, **423 tests**, **0 failures**, **0 errors**, **7 skipped**.
  - The classes were executed in five bounded Surefire batches because a single long tool process exceeded the execution window; every infrastructure `*Test.java` class has a corresponding successful Surefire report.
- `LayerArchitectureTest`: **14/14 PASS**.
- `tools/architecture-check.py`: **PASS**.
- `tools/implementation-completeness-check.py`: **PASS**.
- Full 15-project Maven reactor: offline `test-compile` **PASS**.
- No newer monolithic all-module test baseline is claimed; Iteration 55 remains the latest such baseline in project history.

## Boundary

This iteration changes no public annotation/search contract and no user-facing query syntax. It is a persistence/performance hardening pass. Plugin isolation/permissions (MHL-502+) and the external Windows/GitHub acceptance items remain outside this iteration.
