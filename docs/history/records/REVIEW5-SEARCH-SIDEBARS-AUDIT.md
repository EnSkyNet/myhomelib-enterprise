# Review 5 — author search, sidebars and stability audit

Date: 2026-09-01

## Scope

This review integrates the latest UX requirements into the existing P0/P1 correction system without redesigning stable import, Lucene, Reader parsing, archive-safety, migration or backup/restore subsystems.

Required behavior:

1. Author search results are rendered in the left navigation sidebar, not in the central Search workspace.
2. Selecting an author in the left sidebar opens the canonical Author workspace in the center, where that author's paged books are displayed.
3. The right details sidebar can be hidden/restored.
4. Reader mode can hide/restore both left and right sidebars.
5. Existing working behavior must be preserved and changes must be regression-guarded.

## Implemented changes

### Search / author navigation

- Added `GlobalSearchResult` instead of `Map<String,Object>` for global search payloads.
- Added bounded `SearchService.searchAuthors(query, limit)` backed by repository-side search.
- `SearchWorkspaceController` routes author hits into `NavigationPanelController.showAuthorSearchResults(...)`.
- The old central author result section remains in FXML only for baseline compatibility, but is `visible=false` and `managed=false` and is never populated as the active author-search UI.
- Explicit advanced `authorFilter` also populates the left sidebar with bounded author results.
- Author selection continues through the existing `DefaultNavigationService` callback and `WorkspaceManager.showAuthorWorkspace(authorId)`, so the central view uses the existing paged Author workspace instead of introducing a second book-list implementation.
- No first book is automatically selected in search results.
- Advanced paging now has explicit `hasPrevious` / `hasNext` guards.
- Programmatic search-field updates no longer schedule a duplicate debounced search; an explicit search also cancels a pending debounce.

### Main layout

- Added `MainLayoutService` as the single owner of left/right sidebar visibility.
- Both `visible` and `managed` are updated, so hidden sidebars no longer reserve BorderPane layout space.
- Added `View -> Left sidebar` and `View -> Right sidebar` check menu items.
- Search automatically reveals the left sidebar when author matches must be shown.

### Reader

- Reader toolbar now has independent controls for left and right sidebars.
- Reader module stays UI-shell agnostic: it exposes callbacks only; actual layout state remains in `MainLayoutService` in the UI module.
- The global View menu remains an alternate way to restore sidebars even when the Reader toolbar itself is hidden.

## Additional defects fixed during audit

### Search result type safety

`SearchWorkspaceController` no longer performs unchecked casts from `Map<String,Object>`. The application layer now returns an immutable typed record.

### Statistics error handling

`SqliteStatisticsRepository` no longer catches broad `Exception` around database access. It catches `DataAccessException` and detects SQLite busy/locked conditions through the cause chain. This also restores the v7.1 release invariant that failures must not silently collapse to fake zero statistics.

### Startup status-bar DB access

The initial statistics cache read is now submitted to `UiBackgroundExecutor`, because even an O(1) SQLite cache read can hit `SQLITE_BUSY`; its retry path must not sleep on the JavaFX Application Thread. The UI update also checks that the active collection has not changed while the background read was running, so stale statistics are discarded.

### Hikari shutdown cleanup

`DatabaseConnectionCleanup` previously used reflection to call a non-existent `HikariDataSource.evictConnections()` method, then closed the same pool again through `CollectionManager`, followed by `System.gc()` and `Thread.sleep(200)`. The cleanup now uses `CollectionManager.forceCloseCurrentCollection()` as the single pool owner and removes the forced GC/sleep path.

The broader Hikari audit also confirmed that collection-scoped repositories generally resolve the current `JdbcTemplate` dynamically, while metadata repositories intentionally retain `metadataJdbcTemplate`; `CollectionTransactionConfig` already delegates connections to the currently active collection. Therefore no risky forced eviction of active connections was introduced.

## Regression protection added

`tools/functional-regression-check.py` now ratchets:

- author results routed to left navigation;
- author selection routed to the center Author workspace;
- server-side typed author search;
- single-owner sidebar visibility;
- Reader left/right sidebar controls.

`tools/startup-nonblocking-check.py` now additionally requires the initial statistics read to stay off the JavaFX thread.

## Validation completed

Passed after the changes:

- `tools/review4-critical-behavior-check.py`
- `tools/functional-regression-check.py` — 25 critical behavior ratchets
- `tools/ui-function-reachability-check.py`
- `tools/startup-nonblocking-check.py`
- `tools/architecture-check.py`
- `tools/implementation-completeness-check.py`
- `tools/lucene-search-audit-check.py`
- `tools/online-book-runtime-check.py`
- `tools/catalog-lifecycle-regression-check.py`
- `tools/reader-large-book-persistence-check.py`
- `tools/author-search-normalization-check.py`
- `tools/build-check-v7.py`
- `tools/static_release_check.py`
- stages 3–22 checks executed in this review passed
- `tools/stage25a-ui-orchestration-check.py`
- `tools/stage25b-reader-refactor-check.py`
- `tools/stage25c-search-sync-refactor-check.py`
- `tools/validate-language-catalogs.py` — uk/en/bg, 203 UI keys each

`stage23-cross-platform-release-check.py` could not run because the supplied source archive contains no `dist/` directory. This is a packaging-input limitation, not a source-code failure.

## Validation limitation

A full Maven compile/test run could not be executed in this environment. The project does not contain a usable local Maven distribution and Maven Wrapper attempted to download Maven from `repo.maven.apache.org`, while outbound network access is blocked. Static source/FXML/release checks therefore passed, but a final local `mvn clean verify` and JavaFX runtime smoke should still be run in the normal development environment before release.
