# MYHOMELIB — History: Development Stages

This is a compact historical index. It is not the current product contract. Exact original changelogs and validation reports are preserved in `docs/archive/source-notes/root/`.

## Stage 1 — Architecture Baseline Changelog

Focus: Completed; Intentionally not changed.

- Corrected module count to 11 Maven modules.
- Documented the 8 product/runtime modules and 3 verification/tool modules.
- Documented the exact direct production dependency graph.
- Documented Reader portable-vs-JavaFX package boundary.

Sources: `STAGE-1-CHANGELOG.md` and `STAGE-1-VALIDATION.md` in the archive.

## Stage 2 — Navigation Core

Focus: Scope; Added; Changed; Documentation.

- `NavigationMode`
- `AUTHORS`
- `SERIES`
- `GENRES`

Sources: `STAGE-2-CHANGELOG.md` and `STAGE-2-VALIDATION.md` in the archive.

## Stage 3 — Year / Language / Archive Navigation

Focus: Scope; Added; Localization; Architecture impact.

- `AUTHORS`
- `SERIES`
- `GENRES`
- `YEARS`

Sources: `STAGE-3-CHANGELOG.md` and `STAGE-3-VALIDATION.md` in the archive.

## Stage 4 — Keywords / Groups / Reviews Navigation

Focus: Scope; Added; Tests and guards; Localization.

- `AUTHORS`
- `SERIES`
- `GENRES`
- `YEARS`

Sources: `STAGE-4-CHANGELOG.md` and `STAGE-4-VALIDATION.md` in the archive.

## Stage 5 Changelog — Recent / AlreadyRead / History + navigation history

Focus: Scope; Реалізовано; Архітектурні рішення.

- Додано application-level `ReadingHistoryService` і окремий `ReadingHistoryPort` contract для журналу відкриттів.
- Додано меню `Недавні книги`, яке динамічно показує останні книги разом з локальним timestamp `dd.MM.yyyy HH:mm`.
- Вибір книги з Recent відкриває її у вбудованому Reader.
- У журнал потрапляє лише успішне відкриття книги в `NewReaderWorkspaceController`; невдале відкриття не створює history entry.

Sources: `STAGE-5-CHANGELOG.md` and `STAGE-5-VALIDATION.md` in the archive.

## Stage 6 Changelog — Online update model: data layer

Focus: Scope; Реалізовано; Архітектурні рішення; Post-delivery compile hotfix.

- `catalog_sources` — стабільний logical source, sanitized location, SHA-256 fingerprint і монотонна `source_revision`;
- `catalog_book_state` — per-book catalog fingerprint/storage metadata, `first_seen_revision`, `last_seen_revision` і downloaded baseline;
- `followed_authors` — явний persistent follow-state автора;
- `catalog_update_events` — pending/acknowledged event state для двох Stage 6 update types.

Sources: `STAGE-6-CHANGELOG.md` and `STAGE-6-VALIDATION.md` in the archive.

## STAGE 7 CHANGELOG — Online Updates UI + large-catalog stabilization

Focus: 0. Required stabilization completed before Stage 7; 1. Stage 7 — Updates navigation; 2. Stage 7 — scalable update snapshot; 3. Stage 7 — Updates Workspace.

- `AuthorRepository` now exposes `findByInitial(char)`, `findFirstInitial()`, `countByInitial(char)` and bounded `searchByName(...)`.
- `DefaultNavigationQueryService` no longer calls `authorRepository.findAll()` for AUTHORS.
- AUTHORS startup resolves the first available initial and loads only that initial from SQL.
- `NavigationPanelController` keeps `currentLetter` nullable for AUTHORS and re-resolves the first initial after import.

Sources: `STAGE-7-CHANGELOG.md` and `STAGE-7-VALIDATION.md` in the archive.

## Stage 8 — Unified Filter Engine — Changelog

Focus: Реалізовано; Додаткові виправлення, знайдені під час Stage 8.

- Додано application-level `BookFilterSpec` з persisted global state (`BookFilterStateService`).
- Підтримані критерії: language, year range, format, local/online, read/unread, rating min/max, hide-unrated, quick field/value.
- Додано AND/OR semantics всередині unified filter group.
- `BookQuery` та `SearchRequest` отримали `filterSpec`.

Sources: `STAGE-8-CHANGELOG.md` and `STAGE-8-VALIDATION.md` in the archive.

## Stage 9 — Table Profiles + Quick Filters — Changelog

Focus: Реалізовано; Data-layer correctness.

- Persisted table profile per workspace/view: width/order/visibility/sort.
- Окремі profiles для series, genre, year, language, archive, keyword, group, reviews, all-books, already-read, history.
- Quick filter UI з полями: all/title/author/series/genre/keyword/publisher/file.
- Active filter indicator + filtered total count.

Sources: `STAGE-9-CHANGELOG.md` and `STAGE-9-VALIDATION.md` in the archive.

## Stage 10 — Rich Book Details / Annotation — Changelog

Focus: Реалізовано; Поведінка metadata.

- `BookDetailsController` переведено на асинхронне завантаження повного `BookDto` за ID; details більше не залежать від урізаного DTO рядка таблиці.
- `BookDto` / `BookMapper` розширено повними author/genre DTO, translators, city, source URL, LibID та library rate.
- Details pane перебудовано як scrollable structured metadata panel.
- Authors / series / genres / keywords / publisher стали deep links у відповідні navigation/search flows.

Sources: `STAGE-10-CHANGELOG.md` and `STAGE-10-VALIDATION.md` in the archive.

## Stage 11 — Extra-format Metadata and Images — Changelog

Focus: Реалізовано; Безпека / межі.

- Додано read-only metadata inspection для MOBI/AZW/AZW3 через PalmDB/MOBI/EXTH:
- title;
- author;
- publisher;

Sources: `STAGE-11-CHANGELOG.md` and `STAGE-11-VALIDATION.md` in the archive.

## Stage 12 — Collection AutoUpdater

Focus: Scope; Changes; Large-library behavior.

- Added metadata migration `db/migration_meta/V3__collection_source_watch.sql`.
- Added persisted `CollectionSourceState` with source path, enabled flag, debounce, baseline/observed SHA-256 fingerprints, status and update flag.
- Added `CollectionSourceMonitorPort` and `CollectionAutoUpdateUseCase`.
- Added `CollectionSourceMonitorAdapter` using Java NIO `WatchService`.

Sources: `STAGE-12-CHANGELOG.md` and `STAGE-12-VALIDATION.md` in the archive.

## Stage 13 — Collection Cleaner / Repair

Focus: Scope; Analyzer; Safe repair; Legacy destructive path removed.

- SQLite `PRAGMA quick_check` status;
- missing local book files;
- invalid or unreadable ZIP/archive-entry references;
- physical library files not referenced by the catalog;

Sources: `STAGE-13-CHANGELOG.md` and `STAGE-13-VALIDATION.md` in the archive.

## Stage 14 Changelog — ActionRegistry + configurable hotkeys

Focus: Implemented; Compatibility.

- Added persisted `ActionPreference` state through application-level `ActionSettingsService`; UI does not access the settings output port directly.
- Added conflict and syntax validation using JavaFX `KeyCombination` before preferences are saved.
- Added command-customization dialog with shortcut editing, visibility toggles and reset-to-defaults behavior.
- Main menu items are wired through the registry while existing controller methods remain the behavior handlers.

Sources: `STAGE-14-CHANGELOG.md` and `STAGE-14-VALIDATION.md` in the archive.

## Stage 15 Changelog — User scripts / book actions

Focus: Implemented; Safety/behavior.

- Added persisted named `BookActionProfile` objects with ordered `BookActionCommand` entries.
- Each command supports executable, argument template, working directory and optional wait-for-exit behavior.
- Commands run directly with `ProcessBuilder`; no `cmd.exe`, `sh`, shell pipeline or shell re-tokenization is used.
- Added exact argv preview that never executes a process.

Sources: `STAGE-15-CHANGELOG.md` and `STAGE-15-VALIDATION.md` in the archive.

## Stage 16 Changelog — Export/device profiles

Focus: Implemented; Compatibility.

- Added one-time migration of legacy global filename/subfolder/post-command settings into an editable default export profile.
- Added collision policy `ASK` in addition to overwrite/skip/auto-rename. The worker requests a per-file decision from the JavaFX UI without running export work on the UI thread.
- Existing batch progress/cancellation was preserved and connected to the global status bar as well as the export dialog.
- Filename/subfolder templates are now profile-specific. Supported fields include author/title/series/series-number/year/language/publisher/book ID.

Sources: `STAGE-16-CHANGELOG.md` and `STAGE-16-VALIDATION.md` in the archive.

## Stage 17 Changelog — OPDS core

Focus: Implemented; Architectural guarantees.

- Added read-only application contracts for OPDS catalog queries and downloads.
- Added bounded SQLite OPDS query adapter with `LIMIT/OFFSET` pagination for authors, series, genres and book/search feeds; list page size is capped at 100.
- Implemented OPDS endpoints for root, authors, series, genres, search, a single book and download.
- Added `/health` endpoint for lifecycle checks.

Sources: `STAGE-17-CHANGELOG.md` and `STAGE-17-VALIDATION.md` in the archive.

## Stage 18 Changelog — OPDS lifecycle UI

Focus: Implemented; Security/UX decisions.

- Added desktop OPDS lifecycle service with explicit start/stop/status handling outside JavaFX controllers.
- Added persisted OPDS settings for bind address, port, Basic Authentication and autostart.
- Default bind is `127.0.0.1:8088`.
- Added optional HTTP Basic Authentication.

Sources: `STAGE-18-CHANGELOG.md` and `STAGE-18-VALIDATION.md` in the archive.

## Stage 19 Changelog — Reader settings UX (AlReader-like)

Focus: Implemented; Compatibility.

- Replaced the single long Reader settings grid with categorized tabs: Typography, Colors, Layout, Navigation and Status.
- Added built-in presets: Standard, Comfortable, Compact and Night.
- Added live preview while the dialog is open. Cancel restores the exact settings that were active when the dialog opened.
- Added reset controls per settings category instead of a single destructive all-settings reset.

Sources: `STAGE-19-CHANGELOG.md` and `STAGE-19-VALIDATION.md` in the archive.

## Stage 20 Changelog — Reader engine quality

Focus: Implemented; Safety/performance decisions.

- Visual hyphenation does not modify source text offsets, preserving bookmarks, search results and persisted positions.
- Refined EPUB3 nav and EPUB2 NCX handling so `chapter.xhtml#fragment` targets resolve to the exact element text offset instead of only the start of the spine document.
- Added Shift+drag current-page text selection with visual highlight and Ctrl+C clipboard copy. Existing swipe/tap navigation remains unchanged when Shift is not held.
- Added layout regression fixture for dictionary-driven hyphenation and source-offset stability.

Sources: `STAGE-20-CHANGELOG.md` and `STAGE-20-VALIDATION.md` in the archive.

## Stage 21 Changelog — Context help + genre localization

Focus: Implemented; Compatibility and safety.

- Added central `HelpTopicRegistry` mapping workspaces and dialog contexts to help topics; controllers/workspaces no longer need to know bundled resource paths.
- `HelpService` now prefers localized Markdown help and keeps TXT/HTML plus Ukrainian fallback for compatibility.
- Added 63 bundled Markdown help pages across Ukrainian, English and Bulgarian, including navigation, updates, filters, details, maintenance, actions, OPDS and backup topics.
- Upgraded shipped `Lang/*.json` catalogues to schema version 2 while keeping schema-v1 packs readable with safe fallback.

Sources: `STAGE-21-CHANGELOG.md` and `STAGE-21-VALIDATION.md` in the archive.

## Stage 22 Changelog — Versioned user-data backup/restore

Focus: Goal; Added; Backup/restore safety fixes; UI/documentation.

- `UserDataTransferPort` with portable `user-data.json` schema version 2.
- Streamed export/import implementation `VersionedUserDataTransferAdapter`.
- Portable sections for:
- rating, reading progress and review;

Sources: `STAGE-22-CHANGELOG.md` and `STAGE-22-VALIDATION.md` in the archive.

## Stage 23 Changelog — Cross-platform CI/release

Focus: Goal; Added; Changed; Runtime network contract.

- `.github/workflows/ci-release.yml` with a Windows/Linux/macOS JDK 21 matrix.
- Full `./mvnw clean verify -Pproduction` gate on every platform before packaging.
- Linux offline architecture, migration, large-library and Stage 3–22 regression guards in CI.
- `package-portable.sh` and `package-portable.ps1`:

Sources: `STAGE-23-CHANGELOG.md` and `STAGE-23-VALIDATION.md` in the archive.

## Stage 24 Changelog — Performance Baseline

Focus: Scope; Added; Release integration; Measured baseline in this validation environment.

- `tools/stage24-performance-baseline.py`
- deterministic synthetic catalogues at 100k / 500k / 1M books;
- applies the real SQLite V1–V33 migration set;
- measures startup-like DB open/count, author navigation, filtered author navigation, language facet, first/deep/filtered pages, text fallback search and stable LibID lookup;

Sources: `STAGE-24-CHANGELOG.md` and `STAGE-24-VALIDATION.md` in the archive.

## Stage 25A Changelog — Main/Table/Navigation UI Orchestration Refactor

Focus: Scope; Refactored; Regression protection; Behaviour/performance contract.

- Added `MainNavigationCoordinator` for main-shell navigation commands, recent/history menu orchestration and navigation-mode actions.
- Added `MainBookCommandCoordinator` for selected-book commands such as internal/external open, local-copy removal, metadata editing and deletion.
- Reduced `MainController` from 793 lines before the refactor to 647 lines while preserving its FXML-facing handler surface.
- Removed `MainController` references from:

Sources: `STAGE-25A-CHANGELOG.md` and `STAGE-25A-VALIDATION.md` in the archive.

## Stage 25B Changelog — Reader Internals Targeted Refactor

Focus: Scope; ReaderCanvas; TextLayoutEngine; Fb2StreamingParser.

- Extracted bounded previous-page state to `ReaderPageHistory`.
- Extracted selection offsets, hit-testing, selection overlay and clipboard copy to `ReaderSelectionController`.
- Source text offsets and Shift-drag/Ctrl+C behaviour are unchanged.
- Size reduced from 772 to 701 lines.

Sources: `STAGE-25B-CHANGELOG.md` and `STAGE-25B-VALIDATION.md` in the archive.

## Stage 25C Changelog — Lucene Search / Folder Sync Targeted Refactor

Focus: Scope; Lucene search refactor; Folder sync refactor; Bug fixed during refactor.

- Reduced `LuceneSearchService` from 662 lines before Stage 25C to 391 lines.
- Extracted immutable catalogue snapshot → Lucene schema mapping to `LuceneDocumentMapper`.
- Extracted application `BookFilterSpec` → Lucene query translation to `LuceneUnifiedFilterBuilder`.
- Extracted classic MyHomeLib query compatibility/normalization to `LuceneQueryNormalizer`.

Sources: `STAGE-25C-CHANGELOG.md` and `STAGE-25C-VALIDATION.md` in the archive.

## Final stage checkpoint

The historical roadmap reached Stage 25C. Later RC work focused on runtime/download/Reader/UI correctness rather than adding another numbered stage. Current behavior is documented only in the six active root documents.
