# Iteration 60 — Continue Reading across devices

Date: 2026-09-12
Status: DONE locally
Backlog: MHL-410

## Delivered

- Added the application-level `ContinueReadingService` and `ContinueReadingItemDto` as the shared desktop/web read model for the active reading shelf.
- Added SQLite `SqliteContinueReadingRepository`: only live books with `0 < progress < 100` are shown, sorted by most recently updated progress and bounded to 50 entries.
- Migration V59 adds `reading_progress.last_device` plus an active/recent lookup index. Existing rows migrate to `desktop` without losing progress.
- Desktop Reader writes `last_device=desktop`; Web Reader writes `last_device=web`.
- Added `ReadingProgressSyncProjector`: an already-resolved `READING_PROGRESS` sync record is projected back into the same shared `reading_progress` store with the originating `deviceId`. Tombstones remove the progress row.
- Desktop Dashboard now renders a real Continue Reading shelf with title, progress, last device/time and direct open action instead of a single session-only book.
- `/web/continue` uses the same application shelf and renders progress, chapter, last device/time and direct resume links.
- Completed books (`percent >= 100`) and deleted books are excluded from the active shelf.
- Versioned user-data backup/restore now preserves `lastDevice` for reading progress.

## Verification

- `ReadingProgressSyncProjectorTest`: 2/2 PASS.
- `SqliteContinueReadingRepositoryTest`: 2/2 PASS, including remote progress projection and completion removal.
- `DatabaseMigrationMatrixTest`: 1/1 PASS and advances all representative schemas through V59.
- `WebLibraryRendererTest`: 4/4 PASS.
- `JdkOpdsServerTest`: 15/15 PASS, including authenticated `/web/continue` progress/device/time rendering.
- Combined targeted MHL-410 acceptance: 24/24 PASS.
- `VersionedUserDataTransferAdapterTest`: 9/9 PASS after adding last-device backup compatibility.
- `LayerArchitectureTest`: 13/13 PASS.
- `tools/architecture-check.py`: PASS.
- `tools/implementation-completeness-check.py`: PASS.
- `tools/check-critical-ui-localization.py`: PASS.
- `tools/static_release_check.py`: PASS; 59 SQLite migrations recognized.
- `git diff --check`: PASS.

## Validation boundary

Iteration 60 does not claim a new full 13-module reactor. The latest full-reactor baseline remains Iteration 55: 13/13 modules BUILD SUCCESS, 907 tests, 0 failures/errors, 12 skipped. External MHL-010/011/012/017/018/019 remain OPEN pending real Windows/GitHub evidence.
