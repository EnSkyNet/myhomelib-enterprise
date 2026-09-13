# Stage 22 Changelog — Versioned user-data backup/restore

Date: 2026-08-25

## Goal

Make user data portable across catalogue refresh/re-import while retaining compatibility with existing full-database backups. Book-scoped state is mapped by stable LibID rather than depending on an internal database row ID.

## Added

- `UserDataTransferPort` with portable `user-data.json` schema version 2.
- Streamed export/import implementation `VersionedUserDataTransferAdapter`.
- Portable sections for:
  - rating, reading progress and review;
  - detailed reading position;
  - reading history and reading statistics;
  - bookmarks;
  - groups/favorites and memberships;
  - saved searches;
  - unified filter settings;
  - global Reader preferences;
  - per-book Reader overrides.
- Stable book identity resolution: LibID first, old internal book ID only as same-catalogue fallback.
- Bounded 50,000-entry LRU identity cache during restore to avoid unbounded heap growth on large libraries.
- Schema migration chain for portable manifests; previous v1 `ratings`/`reading` manifests migrate sequentially to v2.
- Restore mode **User data only** for applying a profile to an already refreshed/re-imported catalogue without replacing catalogue metadata.
- Regression tests for changed internal IDs with the same LibID, idempotent restore and v1 migration.

## Backup/restore safety fixes

- Live SQLite backup now uses `VACUUM INTO`, capturing committed WAL state consistently instead of copying the active `.db` file directly.
- Full restore first stages the database to a sibling `.restore.tmp` file while the current catalogue remains available.
- SQLite handles are closed only for the final replacement; `ATOMIC_MOVE` is used where supported with a safe replace fallback.
- The collection is reopened in `finally`, including failure paths.
- Restored database-only backups are passed through the normal sequential Flyway migration chain after reopen.
- Existing legacy backups that contain only a database file remain supported.
- Removed the unsafe UI sequence that closed the current collection before the application service captured its identity.
- Backup/restore option values are captured on the JavaFX thread before worker execution.

## UI/documentation

- Backup dialog explicitly offers **Versioned user data (LibID)**.
- Restore dialog separates full database restore from **user-data-only** restore.
- Help pages were expanded in Ukrainian, English and Bulgarian.
- README and ARCHITECTURE document the Stage 22 portability and safety boundary.
- Added `tools/stage22-versioned-user-data-check.py` with WAL snapshot and migrated-schema smoke checks.

## Legacy MyHomeLib compatibility

The existing HLC2 attach path already imports `LibID`, rating, progress and review where those legacy columns are available. Stage 22 intentionally does not guess unspecified legacy bookmark/group formats; portable restore uses data that the source format actually exposes.
