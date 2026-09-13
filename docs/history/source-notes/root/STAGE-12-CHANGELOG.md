# Stage 12 — Collection AutoUpdater

## Scope

Stage 12 adds a safe FLibrary-style local source watcher for collection catalog files without watching the entire library tree.

## Changes

- Added metadata migration `db/migration_meta/V3__collection_source_watch.sql`.
- Added persisted `CollectionSourceState` with source path, enabled flag, debounce, baseline/observed SHA-256 fingerprints, status and update flag.
- Added `CollectionSourceMonitorPort` and `CollectionAutoUpdateUseCase`.
- Added `CollectionSourceMonitorAdapter` using Java NIO `WatchService`.
- Watch scope is intentionally limited to the parent directory of one explicitly configured source file; unrelated filesystem events are ignored.
- Matching events use rescheduling debounce; default is 60 seconds, preventing update storms on repeated writes/renames.
- Source validation checks existence/readability and opens INPX/ZIP via `ZipFile` before hashing.
- Manual `Check now` is available as fallback when watcher delivery is unavailable.
- A new SHA-256 fingerprint produces `CollectionSourceUpdateAvailableEvent` only when it differs from the current baseline and is a newly observed fingerprint.
- Repeated checks of the same changed fingerprint do not republish notifications.
- Successful import of the configured source updates the persisted baseline through `ImportFinishedEvent` handling.
- Background notifications are surfaced through the global status bar even when Collection Workspace is not open.
- Collection Workspace now contains source selection, enable/disable, save and manual refresh controls.
- Metadata SQLite connections now explicitly enable foreign keys so watch rows are removed with their collections.

## Large-library behavior

The watcher never recursively scans a 100k/500k/1M-book root on every filesystem event. Only the configured source filename is matched by `WatchService`; SHA-256/validation happens once after debounce.
