# MyHomeLib Enterprise v7.1 — Functional Regression Audit

Date: 2026-08-30
Base under test: `online-fix-6`
Resulting worktree: `functional-regression-fix-7`

## 1. What was compared

1. Current `online-fix-6` against the last pre-IDEA runtime checkpoint (`2026-08-30-1820`).
2. Current code against `myhomelib-enterprise-master.zip` (older functional reference before the large-collection refactor).
3. User-facing FXML files, `fx:id`, FXML event bindings, Java UI entry points, Reader actions, online collection paths, update tracking and collection operations.
4. The user-reported regression list (`зауваження2.txt`).

## 2. Recent merge integrity

Between checkpoint `1820` and `online-fix-6`:

- missing production Java files: **0**;
- missing UI/Reader resource files: **0**;
- missing FXML files: **0**;
- missing FXML event handlers: **0**;
- missing FXML `fx:id`: **0**;
- removed Java method names: only `FolderSyncInpxSupport.Finalization.success/failure`, which were renamed during the Java record accessor compile fix; no user-facing feature depended on those factory names.

Conclusion: the later `compile-fix-3 -> online-fix-6` chain did **not** delete the previously present UI feature set.

## 3. Older master comparison — removed items

The older master contains 31 production classes that are no longer present. They fall into architectural replacement/cleanup groups rather than 31 lost user features:

- old search presenter/state: `BookSearchPresenter`, `SearchViewModel` -> current Search Workspace / server-paged query path;
- redundant orchestration: `NavigationHistoryService`, `BackgroundTaskService` -> `WorkspaceManager` history and shared UI executor;
- dead/test Reader infrastructure: `ReaderException`, `SimpleResourceRepository`;
- superseded mapper/update types: `BookMapperHelper`, `CatalogUpdateRecord`;
- superseded ports: `ReaderBookResourcePort`, `UserDataExchangePort`, `ReadingStatisticsPort`, generic `Cache`, `CacheRefresherPort`, `DictionaryCachePort`, `ImportReader`;
- old collection-membership API: `AddBookToCollectionUseCase`, `LoadCollectionBooksUseCase`, `IsBookInCollectionUseCase`, `RemoveBookFromCollectionUseCase` -> physical per-collection SQLite/storage and `CopyBooksBetweenCollectionsUseCase`;
- superseded adapters/cache/startup/import readers: `JsonUserDataExchangeAdapter`, `DatabaseInitializer`, `BackgroundWarmup`, `CaffeineCache`, `DictionaryCache`, `CacheFactory`, `CacheRefresherAdapter`, `CacheConfig`, `ReaderResourceConfig`, `ReaderBookResourceAdapter`, `InpxImportReader`, `Fb2ImportReader`.

The FXML/action comparison against old master found only four removed user controls:

1. Backup `Cancel` — intentionally removed because it did not cancel the real operation.
2. Restore `Cancel` — intentionally removed for the same reason.
3. `Add book to collection` — old link-style collection model, no longer valid.
4. `Remove book from collection` — same obsolete model; current replacement is physical copy between collections.

## 4. Real regression found and fixed

### Author workspace series grouping

The older functional implementation had `buildSeriesRows()` which rendered:

`Series -> book #1 -> book #2 -> ... -> books without series`.

During the server-pagination refactor this method was removed and the Author Workspace default sort became TITLE. That was a real behavior regression.

Fixed in `functional-regression-fix-7` without returning to an all-author in-memory load:

- Author Workspace default is now `SortBy.SERIES`;
- explicit `За серією` UI action restored;
- current server page is rendered with `SeriesGrouping` headers;
- group-header rows cannot be opened/read as books;
- SQL series ordering now uses series name + `sequence_number` + title, with books without series last;
- pagination remains server-side and bounded.

## 5. Requested online-book open UX — added

The old/master code silently called `ensureLocal()` before opening. The requested confirmation was not actually present in the older master either, so this is an explicit UX improvement rather than restoration.

Now:

1. physically present book -> open immediately;
2. missing book -> dialog `Книга відсутня`;
3. user confirms -> download and persist;
4. Reader/external reader opens only after successful download;
5. user declines -> no download, no Reader open;
6. explicit `Download` command still downloads without a redundant confirmation.
7. the `WorkspaceManager.showNewReaderWorkspace(BookId)` entry point is now guarded too, so Recent Books, back/forward Reader history and programmatic UI navigation cannot bypass the physical-file check.

The physical-file lookup is authoritative; the DTO `local` flag alone is not trusted.

## 6. User regression list status

| Item | Current status after audit |
|---|---|
| Words occasionally merge in Reader | **OPEN / runtime fixture required.** Parser has whitespace normalization, but the exact failing FB2/EPUB is required to reproduce the observed rendering defect safely. |
| Theme changed by quick Reader button is not persisted | **PRESENT/FIXED in current code.** `cycleTheme`, zoom, two-page and autoscroll notify `onSettingsChanged`, which is connected to persistent Reader settings. |
| Multi-book/compilation TOC does not show nested chapters | **IMPLEMENTATION PRESENT, runtime issue still to reproduce.** FB2 parser records every section level and TOC dialog preserves level indentation. A concrete failing file is needed to determine why its specific nested chapters are absent. |
| Full Reader settings like AlReader | **PRESENT at current v7.1 scope.** Font, size, line/paragraph spacing, first-line indent, alignment, hyphenation, theme/background/text colors, margins, toolbar, one/two-page, auto-landscape, autoscroll, pinch, 9 tap zones, 9 long-tap zones and swipes are exposed. |
| File size in table workspaces | **PRESENT.** Main table, tree table and Author Workspace contain file-size columns. |
| Series grouping/sequence display | **REGRESSION FOUND AND FIXED** in this pass. |
| Language configured through text/external files | **PRESENT.** Active language is persisted in `config/language.txt`; available translations are loaded from external `Lang/<code>.json`. |
| Online collection: show updated downloaded books by author | **PRESENT.** Update tracking distinguishes `UPDATED_DOWNLOADED_BOOK`, groups pending items by author, and the Updates Workspace displays that category. Followed-author new books are also tracked. |

## 7. New anti-regression ratchet

Added:

- `docs/release/FUNCTIONAL-UI-BASELINE-v7.1.json`;
- `tools/functional-regression-check.py`.

The guard retains the complete current FXML file/action/id baseline and checks critical behavior markers for:

- online-open confirmation;
- series grouping and SQL sequence ordering;
- ConnectionScript UI;
- current Flibusta protocol;
- Reader quick-setting persistence;
- nested TOC foundation;
- file-size UI;
- external language configuration.

This specifically catches the failure mode where a controller method and its UI control are both deleted together and an ordinary bidirectional FXML checker would otherwise see no dangling reference.

## 8. Current conclusion

No evidence exists that the recent compile/online merge chain broadly deleted previously working features. One older functional regression was confirmed: Author Workspace series grouping. It is restored in `functional-regression-fix-7` with server-pagination safety retained.

Two Reader observations remain runtime defects to reproduce with the exact affected book: intermittent merged words and missing nested chapters for a specific compilation file. They must not be marked fixed from static inspection alone.
