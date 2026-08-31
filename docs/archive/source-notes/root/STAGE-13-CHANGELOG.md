# Stage 13 — Collection Cleaner / Repair

## Scope

Stage 13 replaces destructive one-click integrity repair with an explicit `Analyze -> Preview -> Dry run -> Apply` maintenance workflow for the active collection.

## Analyzer

The new `CollectionMaintenancePort` / `CollectionMaintenanceUseCase` / `CollectionMaintenanceAdapter` reports:

- SQLite `PRAGMA quick_check` status;
- missing local book files;
- invalid or unreadable ZIP/archive-entry references;
- physical library files not referenced by the catalog;
- authors without books;
- genres without books;
- deterministic duplicate books (same non-empty LibID and exact physical storage identity).

Filesystem/result materialization is bounded: issue samples are limited to 500 per category while total counters remain available. Physical orphan scanning is manual only and never runs as part of the watcher hot path.

## Safe repair

- Analyze and Dry run are read-only.
- Apply is only available for the active collection and requires explicit confirmation in UI.
- Issues are re-analyzed before apply so stale issue IDs are not blindly executed.
- A full SQLite backup is mandatory before mutation and is created using `PRAGMA wal_checkpoint(FULL)` + `VACUUM INTO` under `AppPaths.backupsDir()`.
- Missing/unreadable local resources are not removed from catalog metadata; their `local` flag is reset to `0`.
- Orphan authors/genres are deleted only when the no-reference predicate is still true at apply time.
- Exact storage+LibID duplicates are removed only from the reviewed repair sample.
- Physical orphan files are **report-only** and are never automatically deleted.
- After successful repair, SQLite indexes/statistics are refreshed with `REINDEX`, `ANALYZE` and `PRAGMA optimize`.
- SQLite foreign keys are enabled for collection connections.

## Legacy destructive path removed

The old `IntegrityCheckController -> DataIntegrityChecker.fixOrphanedBooks() -> DataIntegrityService.fixOrphanedData()` destructive path has been removed from the application and infrastructure contracts. The legacy integrity dialog remains useful as a report, but directs users to Collection Workspace Maintenance for repair.
