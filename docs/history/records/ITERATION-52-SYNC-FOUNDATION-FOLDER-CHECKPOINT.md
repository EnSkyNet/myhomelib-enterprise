# Iteration 52 — Sync Foundation / Local Folder Checkpoint

Date: 2026-09-12
Status: DONE (local)

## Closed backlog items
- MHL-401 — Versioned sync data model.
- MHL-403 — Local-folder / Syncthing-compatible sync.

## Design
- `SyncRecord` is a storage-neutral versioned user-data mutation with stable sync id, local key, `baseVersion/version` conflict metadata, source device, UTC `Instant`, schema version and tombstone semantics.
- `ChangeSet` is an ordered per-device bundle with sequence/cursor continuity and duplicate-entity rejection.
- `UserDataSyncRecordFactory` covers reading progress, bookmarks, annotations, ratings, groups, favorites and settings. Group sync IDs are explicitly independent from SQLite AUTOINCREMENT IDs.
- `SyncTransportPort` transports `ChangeSet` objects only; live SQLite database files are outside the contract.
- `LocalFolderSyncAdapter` writes same-directory `.part` files, fsyncs, then uses atomic rename to a finalized bundle. Readers ignore partial files.
- Folder lock prevents concurrent writers; duplicate source-device sequence with a different change-set ID fails as a conflict.
- JSON codec is schema-gated and bounded by bundle size, record count and payload field count.

## Acceptance evidence
- MHL-401 model targeted: 8/8 PASS.
- Combined MHL-401/MHL-403 targeted: 14/14 PASS.
- Iteration 52 static contract: 18/18 PASS.
- `git diff --check`: PASS.
- Full offline Maven reactor: 13/13 modules BUILD SUCCESS.
- Full test aggregate: 885 tests; 0 failures; 0 errors; 12 skipped.
- Infrastructure: 402 tests; 0 failures/errors.
- Architecture: 12/12 PASS.
- UI: 85/85 PASS.
- E2E: 14/14 PASS.

## Still open
MHL-402 WebDAV, MHL-404 conflict resolution, MHL-405 encrypted sync payload and subsequent 7.5 tasks remain OPEN. External MHL-010/011/012/017/018/019 also remain OPEN pending real Windows/GitHub evidence.
