# Iteration 52 — Sync foundation + local-folder transport

Date: 2026-09-12

## Scope
- MHL-401 — Versioned sync data model.
- MHL-403 — Local-folder/Syncthing-compatible sync.

## Acceptance
- Stable sync IDs separate from local row IDs where required.
- baseVersion/version + device + timestamp conflict metadata.
- Explicit schema version and delete tombstones.
- User-data types: progress, bookmarks, annotations, ratings, groups, favorites, settings.
- Shared-folder transport writes immutable change bundles, never a live DB file.
- Atomic publish; interrupted `.part` files ignored.
- Cross-instance incremental pull by per-device cursor.
- Lock contention and duplicate device-sequence conflicts fail closed.
