# STAGE 7 CHANGELOG — Online Updates UI + large-catalog stabilization

Date: 2026-08-25
Base: `myhomelib-enterprise-stage-6-online-update-model-data-layer-fixed.zip`

## 0. Required stabilization completed before Stage 7

The Stage 7 UI is built on top of the Stage 6 revision model only after fixing the large-library/import issues found in the supplied archive.

### Author navigation for 700k+ catalogues

- `AuthorRepository` now exposes `findByInitial(char)`, `findFirstInitial()`, `countByInitial(char)` and bounded `searchByName(...)`.
- `DefaultNavigationQueryService` no longer calls `authorRepository.findAll()` for AUTHORS.
- AUTHORS startup resolves the first available initial and loads only that initial from SQL.
- `NavigationPanelController` keeps `currentLetter` nullable for AUTHORS and re-resolves the first initial after import.
- `V32__author_navigation_initial_index.sql` adds an expression index matching the SQLite display-initial expression.
- Full eager author-cache loads were removed from normal startup/warmup/collection switch/restore/INPX paths.
- Legacy author search/warmup paths use bounded repository queries or SQL updates instead of materializing the full author table.

### INPX progress/statistics and memory behaviour

- `progressListener` and `statusConsumer` now propagate from `ImportContext` through `ImportFileUseCase` / `FastImportService` to `InpxImportPipeline`.
- INPX count/progress reporting is throttled instead of issuing a JavaFX callback per record.
- INPX returns the existing full `ImportResult` (`imported/skipped/duplicates/errors/durationMs`) rather than collapsing the result to one `long`.
- Import Workspace keeps a detailed completion summary and drives the global status bar progress.
- Full `dictionaryCache.loadAuthors(authorRepository.findAll())` refreshes were removed.
- Author cache maintenance is incremental and released after import.
- Batch author insertion resolves persistent IDs after `INSERT OR IGNORE`, preventing a transient UUID from being kept in the cache when the row already existed.
- Cancellation closes INPX reader resources and does not leave the import cache retained.
- `ImportController` completion callback now runs after asynchronous import completion instead of immediately after file selection.

### Collection-management correctness

- `selectedCollection` is separated from the active/current collection.
- Rename/delete/properties actions in Collection Workspace operate on the explicit selected collection.
- `CollectionDto` now carries `active`, `allowRename`, `allowDelete`, root/db information and book count metadata.
- Deletion protection is enforced in the application use case, not only in JavaFX button state.
- Main menu uses the cleaner “Manage collections…” workflow for destructive collection operations.
- application state is synchronized when the active collection changes.

## 1. Stage 7 — Updates navigation

- Added `NavigationMode.UPDATES`.
- `DefaultNavigationQueryService` exposes a synthetic Updates node whose `bookCount` is the current pending-update counter.
- Navigation sidebar and menu expose the Updates workspace.
- Existing `NavigationRefreshEvent` now refreshes the sidebar, so successful downloads and collection changes can update the badge/counter globally.

## 2. Stage 7 — scalable update snapshot

New application records:

- `CatalogUpdateItem`
- `CatalogUpdateAuthorGroup`
- `CatalogUpdateSnapshot`

`CatalogUpdateTrackingPort` gained `findPendingUpdateItems(limit, offset)`.

SQLite implementation:

- enriches pending events with book title/local state;
- deterministically chooses one author per event;
- prefers a followed co-author, which makes `NEW_BY_FOLLOWED_AUTHOR` appear under the expected author;
- keeps one event rendered exactly once, so counters are not inflated by multi-author books.

`CatalogUpdateService.pendingUpdateSnapshot()` reads pending updates in bounded pages and produces:

`Author -> New books / Updated books -> books`.

## 3. Stage 7 — Updates Workspace

Added `/view/updates-workspace.fxml` and `UpdatesWorkspaceController`.

Features:

- hierarchy `Author -> New books / Updated books -> Book`;
- total/new/updated counters;
- empty state;
- refresh action;
- double-click/open-book action;
- open-author action;
- download/update action;
- successful download refreshes the tree and sidebar counter.

## 4. Correct update download semantics

`BookDownloadCoordinator.ensureLocal()` intentionally reuses an existing local file. That behaviour is wrong for `UPDATED_DOWNLOADED_BOOK`, because the old local file would prevent the newer catalog revision from downloading.

Stage 7 adds `downloadUpdate(BookDto)` which force-downloads the online bytes even when a local copy exists. `DownloadBookUseCase` then updates local storage and calls `CatalogUpdateTrackingPort.markDownloadedBaseline(bookId)`, which acknowledges the pending event only after a successful download.

## 5. Localization

Added Stage 7 labels to `uk`, `en`, and `bg` drop-in language catalogues and synchronized the bundled first-run copies.

## 6. Tests/checks added

- `CatalogUpdateServiceTest`
- updated `DefaultNavigationQueryServiceTest`
- updated `SqliteCatalogUpdateTrackingAdapterTest`
- `tools/large-library-pre-stage7-check.py`
- `tools/stage7-online-updates-ui-check.py`

