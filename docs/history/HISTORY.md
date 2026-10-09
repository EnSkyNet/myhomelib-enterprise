# MyHomeLib 8.0.0 — consolidated project history
This is the canonical human-readable history of the project. It consolidates the former root iteration reports, release changelogs/test reports and the previous parallel history summaries. It is **historical evidence, not the active product contract**.

Current behavior is defined by the six active root documents. Current release evidence lives under `docs/release/`. Exact low-level iteration/task/checkpoint records remain under `docs/history/records/`; original legacy source notes remain under `docs/history/source-notes/`.

> Historical paths and counts quoted inside archived sections are preserved as they were at that checkpoint. They are not automatically updated to the current release state.


---

## Historical stage summary

**Original source:** `docs/history/MYHOMELIB-HISTORY-STAGES.md`

# MYHOMELIB — History: Development Stages

This is a compact historical index. It is not the current product contract. Exact original changelogs and validation reports are preserved in `docs/history/source-notes/root/`.

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


---

## Historical fixes and regressions

**Original source:** `docs/history/MYHOMELIB-HISTORY-FIXES.md`

# MYHOMELIB — History: Fixes and Regressions

This file summarizes important historical repair passes. It is not a substitute for the current contracts in the root documentation. Original notes are retained under `docs/history/source-notes/root/`.

## Reader repair line

Early Reader fixes corrected the blank-Canvas wiring defect, page-dimension propagation, long-paragraph continuation, reverse navigation and rich inline rendering. Later work added/verified presets, colors, two-page behavior, gestures, autoscroll, exact EPUB navigation anchors, ZIP handling, FB2 whitespace around inline tags and reliable position persistence.

The AlReaderX comparison was used as a gap audit, not as a promise of complete platform parity. Desktop-relevant controls were implemented where appropriate; Android-only/device-specific features remained outside the desktop contract.

Historical sources: `READER_FIXES.md`, `READER-ALREADERX-GAP-v7.1.md`.

## Collection/catalogue lifecycle repairs

Collection fixes addressed metadata loss on activation, unsafe switch order, concurrent-switch ambiguity and stale active metadata after rename/properties edits. Startup transaction fixes separated metadata-database operations from an as-yet-unselected collection database.

Historical sources: `CATALOG-FIXES-2026-08-26.md`, `STARTUP-TRANSACTION-FIX-v6.1.md`.

## INPX and large-import repairs

Large Flibusta/INPX imports exposed indexed author lookup and batching problems. Fixes restored index-friendly predicates, bounded import behavior, safer handling of author text edge cases and measured batch/index-maintenance choices. Performance choices were subsequently re-measured rather than permanently freezing the first tuning value.

Historical source: `INPX-IMPORT-PERFORMANCE-FIXES-2026-08-26.md`.

## Flibusta catalogue update repairs

The online update path originally treated an INPX server root as a direct archive URL, so HTML could be saved as `.inpx` and an invalid import could appear as zero books. The corrected path distinguishes server/baseline/delta semantics and validates remote archives before DB/index mutation.

Historical source: `FLIBUSTA-ONLINE-UPDATE-FIXES-2026-08-26.md`.

## Runtime fixes 9–11

Observed Windows runtime issues led to three targeted repair passes:

- legacy bare HTTP(S) `ConnectionScript` preamble compatibility;
- permanent download-root use instead of transient catalogue-update cache paths;
- avoiding startup Lucene invalidation/full-catalog derived rewrites;
- removal of catalogue-wide startup root normalization and unnecessary series synchronization;
- lazy normalization of old online-book storage metadata;
- restoration of upstream per-book archive layout;
- recovery of effective collection URL from a legacy script preamble;
- visible failure when online source configuration is missing;
- SQLite `busy_timeout` for pooled connections.

Historical sources: `RUNTIME-FIX-9-2026-08-30.md`, `RUNTIME-FIX-10-2026-08-30.md`, `RUNTIME-FIX-11-2026-08-30.md`.

## RC audit fixes after runtime testing

Later RC work corrected issues that stage/static checks alone did not catch reliably:

- unified download/open/Reader routing through `BookDownloadCoordinator` and resource resolution;
- refreshed full DB book metadata before download to avoid lightweight DTO loss (`libId`, paths, online fields);
- removed startup statistics full-scan behavior;
- removed duplicate UI/download lookups and a future-completion race around storage metadata;
- fixed invalid `navigateToAuthor(null)` navigation;
- clarified current-row vs batch checkbox selection and added batch remove/delete flows;
- protected collection/proxy/TLS encrypted secrets from accidental clearing when decryption fails;
- made OPDS/details use physical file availability rather than only `Book.local`;
- kept failed Reader position saves dirty for retry;
- hardened shared-archive deletion and actual archive-entry checks;
- removed unnecessary repeated ZIP validation in one download operation.

## Flibusta server-renamed ZIP member regression

A stricter RC validator temporarily required exact `archiveEntry` equality. Real Flibusta downloads returned valid ZIPs with a server-generated filename such as:

```text
requested: 586491.fb2
actual: Romanovich_Zemli-chudovishch_1_Zemli-chudovishch.586491.fb2
```

HTTP download succeeded, but validation rejected the archive. The correction restored compatibility without reverting to unsafe guessing: exact match first, then unambiguous basename/LibID token resolution, then a single-FB2 fallback. The actual resolved member is propagated through the download result and persisted so Reader/open/cover/resource lookup use the real ZIP structure. The matching algorithm is centralized in `ArchiveEntryNameSupport` to prevent validator/resolver drift.

This behavior is now part of the active contract in `MYHOMELIB-FEATURES.md` and `MYHOMELIB-OPERATIONS.md`.

## Iterations 47–53 stabilization line

Later fixes included Comic Reader lazy archive handling, TTS pause/resume race hardening, JavaFX/AWT headless-test isolation, accessibility contrast/name guards, background indexing queue race fixes, fail-safe candidate content-index rebuilds, sync bundle atomic publication, and WebDAV XML/origin/credential hardening. The exact iteration records are preserved under `docs/history/records/`.
## Iteration 62 annotation FTS performance hardening

The original annotation FTS triggers addressed rows through `annotation_id`, which is intentionally `UNINDEXED` inside FTS5. On large annotation imports this made every trigger refresh scan the FTS table and caused quadratic growth. Flyway V60 preserves V58 as history, rebuilds the FTS index once, introduces a stable `annotation_search_ids` integer key, and refreshes/deletes FTS rows by `rowid`. The mapping uses an explicit `INTEGER PRIMARY KEY` value so SQLite `VACUUM` cannot renumber live annotation keys. Migration, delete/update, 5,000-annotation, backup and `VACUUM` regressions are covered by the infrastructure suite.

## Iteration 63 plugin security boundary

The first plugin SPI checkpoint intentionally left permissions/isolation open. Iteration 63 adds manifest-declared network/filesystem capabilities, exact host approval binding, trusted/untrusted state, disable/quarantine lifecycle and a managed invocation boundary. Plugin exceptions, linkage failures and assertion failures quarantine the plugin instead of escaping to ordinary host control flow. Capability-gated invocations fail closed when the required permission is not approved. Because Java in-process loading is not an OS sandbox, untrusted plugins are rejected for in-process execution rather than given misleading isolation guarantees.

## Iteration 64 plugin SDK contract closure

MHL-503 turns the plugin SPI/security boundary into a reproducible developer workflow. Plugin API 1.2 adds a public `PluginTestHarness` that executes the host's structural loader checks under a synthetic exact-version test approval without altering runtime trust. A new reactor sample module compiles three independent ServiceLoader plugins (dictionary, metadata and export) and validates their declared permissions in CI. `docs/plugin-sdk/` documents authoring, compatibility and the explicit limitation that the in-process permission model is not an OS sandbox.


## Iteration 71 optional AI privacy/security boundary

MHL-510 completes the release-8.0 ecosystem backlog with Plugin API 1.3 and the additive `AI_PROVIDER` service. The application has no built-in/default provider. Host-owned execution requires persistent per-book opt-in plus explicit content/network consent, bounds request/response sizes and owns cancellation/deadlines. Providers can read only declared credential names through `AiProviderContext`; credentials remain namespaced in the existing `SecretStore` abstraction and are never moved into ordinary settings. Network-backed AI plugins must also declare `NETWORK_ACCESS` in the exact approved manifest permission set.


## Iteration 77 evidence schema drift hardening

External acceptance JSON had explicit `schemaVersion` fields but the accepted values lived as duplicated literals across producer/verifier scripts. Iteration 77 centralizes those contracts in `tools/evidence_contracts.py`, makes Python producers derive current versions from the registry and makes verifiers reject missing, legacy, future or wrong-scenario records. The registry is included in the candidate critical-policy digest and Windows acceptance-harness fingerprint so evidence schema policy cannot change without changing candidate-bound integrity evidence.

## Iteration 83 Windows runtime evidence hardening

Real Windows evidence showed two distinct gaps that local string-level checks had missed. TTS discovery diagnostics could be merged into stdout and parsed as voice rows when the PowerShell command failed; discovery now uses an encoded script, strict record framing, separated stderr and non-zero-exit rejection. Annotation Manager FXML also used a JavaFX 21 callback constant as a plain string attribute; the callback is now set in controller code and the invalid FXML literal is regression-guarded.


---

## Historical audits and validation

**Original source:** `docs/history/MYHOMELIB-HISTORY-AUDITS.md`

# MYHOMELIB — History: Audits, Validation and Upgrades

This file summarizes historical audit/release documents that are no longer active specifications. Exact source notes are preserved under `docs/history/source-notes/root/` and `docs/history/source-notes/docs/`.

## Architecture upgrade audits

The v7 and v7.1 architecture passes established the source-neutral catalogue model, additive Flyway upgrade policy, stable IDs/user-data preservation, online revision state, safe networking/download boundaries, Lucene compatibility/fingerprints and the modular dependency rules now consolidated in `ARCHITECTURE.md`.

Sources: `ARCHITECTURE-UPGRADE-v7.md`, `ARCHITECTURE-UPGRADE-v7.1.md`, old `docs/architecture/*` notes.

## Parity and completeness audits

Parity matrices compared the project with earlier MyHomeLib behavior/reference sources while explicitly avoiding literal GPL source reuse. Code-completeness passes searched for no-op/sentinel implementations, misleading compatibility surfaces, dead/unreachable UI/use-case wiring and duplicated behavior. Where a test reflected an obsolete contract, the test was updated instead of reverting correct production code.

Sources: `PARITY_AUDIT.md`, `UPSTREAM-PARITY-MATRIX-v7.1.md`, `CODE-COMPLETENESS-AUDIT-v7.1.md`, `UI-FUNCTION-REACHABILITY-v7.1.md`.

## Functional regression audits

A dedicated functional pass compared later refactors with older working snapshots/FXML/UI paths. It found and repaired real regressions while establishing static/behavior ratchets around author/series navigation, online open/download flows and user-visible reachability.

Source: `FUNCTIONAL-REGRESSION-AUDIT-v7.1.md`.

## Online/download audits

Networking audits covered shared `HttpClient` policy, TLS/proxy secret handling, Range/If-Range resume safety, anti-splice sidecars, cancellation/retry and credential non-leakage. The detailed `ConnectionScript` grammar/macro document has been merged into `MYHOMELIB-OPERATIONS.md` / `MYHOMELIB-DEVELOPMENT.md`.

Sources: `ONLINE-DOWNLOAD-AUDIT-v7.1.md`, `ONLINE-LIBRARY-CONNECTION-SCRIPT-v7.1.md`.

## Performance audit

Performance work separated executed SQLite scale evidence from JVM/Lucene tests that require a connected Maven environment. Stored 100k/500k/1M SQL profiles and query-plan/index guards remain machine-readable; the old prose baseline has been merged into `MYHOMELIB-DEVELOPMENT.md`. Synthetic numbers are regression evidence for their environment, not universal performance claims.

Source: `PERFORMANCE-v7.1.md`, old `docs/PERFORMANCE_BASELINE.md`, raw JSON under `docs/release/`.

## CI/release audit

Cross-platform release work established the JDK 21 Ubuntu/Windows/macOS Maven matrix, `jpackage` app-image creation, headless packaged-launcher `--release-smoke`, tag gating and SHA-256 publication. These requirements are now in `MYHOMELIB-RELEASE.md` and `MYHOMELIB-DEVELOPMENT.md`.

Sources: `GITHUB-CI-v7.1.md`, old `docs/release/CROSS_PLATFORM_RELEASE.md`.

## Upgrade history

The v6 -> v7 and v7 -> v7.1 notes established the backup-first, additive, forward-only Flyway policy and the rule that rollback is performed from a pre-upgrade backup rather than by deleting migrations. Current operational upgrade guidance is in `MYHOMELIB-OPERATIONS.md` and `MYHOMELIB-RELEASE.md`.

Sources: `UPGRADE-FROM-v6.md`, `UPGRADE-FROM-v7.md`.

## Release notes / roadmap checkpoints

The old 1.0.0 and 7.1.0 release notes plus roadmap-completion checkpoint captured state at particular milestones. They are retained as historical evidence only. Current supported behavior is defined by `MYHOMELIB-FEATURES.md`; current validation boundaries are defined by `MYHOMELIB-RELEASE.md`.

Sources: `RELEASE_NOTES_1.0.0.md`, `RELEASE_NOTES_7.1.0.md`, `ROADMAP-COMPLETION.md`, `CONTINUE-AUDIT-v7.1.md`, `NEXT-AUDIT-v7.1.md`.

## Validation principle retained

Historical audit notes repeatedly distinguished "source/static check passed" from "full compiled release validated". That principle remains normative: a connected `./mvnw clean verify -Pproduction` plus real platform CI is required before a formal production release claim.

## 2026-09-12 iteration audit and cleanup

A full completed-task audit confirmed the locally closed implementation through Iteration 52. Iteration 53 WebDAV remained RC pending its full regression. Historical iteration/task/continuation files were consolidated under `docs/history/records/`; the repository root is now restricted to active project documentation and build/runtime scripts. External MHL-010/011/012/017/018/019 remain explicitly OPEN.

## Iteration 54 closure

After the root/history cleanup, the exact cleaned source tree passed the full offline 13-module Maven reactor: 894 tests, 0 failures, 0 errors, 12 skipped. This closed the local MHL-402 WebDAV gate while leaving the six external Windows/GitHub acceptance items open.


---

## Chronological iteration summary

**Original source:** `docs/history/MYHOMELIB-HISTORY-ITERATIONS.md`

# MYHOMELIB — History: Iterations 1–83

This is the canonical chronological index for iteration-level work. Detailed historical records are stored in `docs/history/records/`; older pre-iteration source notes are stored in `docs/history/source-notes/`. The current product contract remains the active root documentation.

| Iteration | Scope | Historical status |
|---:|---|---|
| 01 | Security / settings hardening | DONE |
| 02 | Formats and paths | DONE |
| 03 | Data integrity / Lucene | DONE |
| 04 | Local-copy / cache safety | DONE |
| 05 | OPDS transport guards | DONE |
| 06 | TLS / certificate / encryption envelope | DONE |
| 07 | E2E / PR CI | DONE locally; external CI evidence tracked separately |
| 08 | Supply-chain / source packaging | DONE locally; external evidence tracked separately |
| 09 | Concurrency / executor lifecycle | DONE |
| 10 | JavaFX lifecycle / async loads | DONE |
| 11 | Transactional edit / domain immutability | DONE |
| 12 | Support bundle / temp lifecycle | DONE |
| 13 | SecretStore native credentials | DONE |
| 14 | Critical UI i18n | DONE |
| 15 | Startup orchestration | DONE |
| 16 | Windows acceptance harness | WIP external acceptance |
| 17 | GitHub + Windows acceptance harness | WIP external acceptance |
| 18 | Candidate-bound external acceptance | WIP external acceptance |
| 19 | Release-evidence integrity ratchet | WIP external acceptance |
| 20 | Final operator flow hardening | WIP external acceptance |
| 21 | Candidate-bound acceptance harness | WIP external acceptance |
| 22 | Windows host/session binding | WIP external acceptance |
| 23 | Multi-artifact duplicate foundation | DONE foundation |
| 24 | Duplicate review / merge | DONE |
| 25 | Metadata provider SPI | DONE |
| 26 | Open Library provider | DONE |
| 27 | Google Books provider | DONE |
| 28 | Metadata merge preview | DONE |
| 29 | Batch metadata editor | DONE |
| 30 | Bulk metadata undo | DONE |
| 31 | Folder watcher | DONE |
| 32 | Smart collections | DONE |
| 33 | Custom fields | DONE |
| 34 | Artifact integrity / library health | DONE |
| 35 | Annotation foundation | DONE |
| 36 | Reader annotations | DONE |
| 37 | Annotation manager | DONE |
| 38 | Annotation export + PDF Reader | DONE |
| 39 | Reader text providers | DONE |
| 40 | PDF closure | DONE |
| 41 | Undo / transaction / offline acceptance | DONE |
| 42 | External acceptance hardening | DONE locally; six external MHL gates remain OPEN |
| 43 | Runtime log reliability | DONE |
| 44 | Reader transition efficiency | DONE |
| 45 | Archive traversal efficiency | DONE |
| 46 | Archive streaming materialization | DONE |
| 47 | Comic Reader | DONE |
| 48 | TTS / accessibility / Reader regression | DONE |
| 49 | ContentExtractor / content index / annotation search | DONE |
| 50 | Indexing queue / resource throttling | DONE |
| 51 | Search Everywhere / index health | DONE |
| 52 | Sync foundation / local-folder transport | DONE |
| 53 | WebDAV Sync | DONE locally after final full regression |
| 54 | Repository history/root cleanup + WebDAV closure | DONE locally; 13/13 reactor, 894 tests |
| 55 | Sync conflict resolution / encryption-key rotation | DONE |
| 56 | OPDS 2.0 JSON feeds | DONE locally; module 16/16 + OPDS E2E 2/2 |
| 57 | OPDS scoped token authentication | DONE locally; targeted security/UI 17/17 |
| 58 | Web Library v1 | DONE locally; targeted Web/HTTP 16/16 + architecture/completeness PASS |
| 59 | Web Reader basic EPUB/FB2 | DONE locally; targeted/integration 21/21 + static gates PASS |
| 60 | Continue Reading across devices | DONE locally; targeted 24/24 + backup 9/9 + architecture 13/13 |
| 61 | Plugin SPI core | DONE locally; plugin-api 9/9 + architecture 14/14 |
| 62 | Annotation FTS performance / stable-rowid hardening | DONE locally; infrastructure 423 tests + architecture/completeness PASS |
| 63 | Plugin permissions / isolation | DONE locally; plugin-api 22/22 + architecture/completeness PASS |
| 64 | Plugin SDK / samples / test harness | DONE locally; plugin-api 25/25 + sample module 2/2 + architecture 14/14 + 16-project test-compile |
| 65 | Device profiles / preferred artifact selection | DONE locally; targeted MHL-504 9/9 + Application 247 tests + architecture/completeness PASS + 16-project test-compile |
| 66 | Send to Device completion safety | DONE locally; targeted MHL-504/MHL-505 14/14 + Application 255 tests + architecture 14/14 + static gates PASS + 16-project test-compile |
| 67 | BookConverter SPI / conversion capabilities | DONE locally; targeted conversion/device 20/20 + Application 265 tests + SQLite persistence 2/2 + architecture 14/14 + static gates + 16-project test-compile |
| 68 | Optional calibre CLI adapter | DONE locally; calibre adapter 7/7 + architecture 14/14 + static gates + 16-project test-compile |
| 69 | Audiobook support v1 | DONE locally; new audiobook 16/16 + affected 21/21 + Application 268 + architecture 14/14 |
| 70 | Obsidian/Joplin-compatible Markdown integration | DONE locally; knowledge exporter 9/9 + UI 2/2 + Application 277 + architecture 14/14 |
| 71 | Provider-neutral AI extension point | DONE locally; AI host 7/7 + plugin AI 3/3 + Plugin API 28/28 + Application 284 + architecture 14/14 |
| 72 | 8.0 release hardening / split full-regression consolidation | DONE locally; 1,035 tests split baseline, no new monolithic claim |
| 73 | External acceptance handoff readiness / canonical six-gate runbook | DONE locally; six external gates remain OPEN |
| 74 | Release-candidate source/dist integrity binding | DONE locally; six external gates remain OPEN |
| 75 | Final reviewer evidence self-containment | DONE locally; six external gates remain OPEN |
| 76 | Reviewer Markdown/JSON consistency hardening | DONE locally; six external gates remain OPEN |
| 77 | Versioned external-evidence contract registry | DONE locally; six external gates remain OPEN |
| 78 | Final local preflight / release-candidate preparation | DONE locally; 1,035-test split baseline + Linux packaging smoke + 84 local checks PASS; six external gates remain OPEN |
| 79 | Windows Spring wiring startup hotfix | DONE locally; real-host startup blocker fixed; Application 285 + Bootstrap 17 + Architecture 14 PASS; external gates remain OPEN |

| 80 | Full Spring runtime wiring hardening | DONE locally; full Spring context PASS + 1,038-test split baseline; external gates remain OPEN |
| 81 | Windows export/TTS usability hotfix | DONE locally; real FB2_ZIP + TTS display regressions fixed; 1,042-test split baseline; external gates remain OPEN |
| 82 | Windows workflow/state hotfix | DONE locally; TTS Unicode transport + mass-export refresh/selection + Reader-safe Annotations/Notes fixed; 1,048-test split baseline; external gates remain OPEN |
| 83 | Windows TTS discovery + Annotation Manager FXML hotfix | DONE locally; fail-closed framed PowerShell voice discovery + JavaFX 21 FXML callback fix; 1,049-test split baseline; external gates remain OPEN |

## Current boundary

As of 2026-09-13, Iterations 47–83 are locally closed. Iteration 54 records repository-history cleanup and the final full-regression closure of WebDAV Sync; Iteration 55 adds no-loss conflict handling and sync-key rotation/migration; Iteration 56 adds OPDS 2.0 JSON feeds while preserving OPDS 1.x compatibility; Iteration 57 adds scoped OPDS/API Bearer tokens with one-time secrets, hash-only persistence, last-used metadata and immediate revocation; Iteration 58 adds the authenticated responsive Web Library v1 over the existing HTTPS sidecar; Iteration 59 adds the EPUB/FB2 Web Reader with TOC, theme/font controls and shared desktop/web reading progress; Iteration 60 turns that shared progress into a cross-device Continue Reading shelf with progress, last device/time, sync projection and automatic completion removal; Iteration 61 introduces the dedicated versioned plugin-api module with nine stable extension contracts and explicit core-service override declarations; Iteration 62 hardens annotation full-text indexing by replacing repeated scans of the `UNINDEXED` annotation id with a stable integer rowid mapping, including upgrade and `VACUUM` regression coverage; Iteration 63 adds host-owned plugin permissions/trust, exact approval binding, disable/quarantine lifecycle and managed failure containment; Iteration 64 adds the public plugin CI harness, buildable metadata/dictionary/export samples and authoring/compatibility documentation; Iteration 65 adds application-owned device profiles and preferred existing-artifact selection for removable-device export; Iteration 66 preserves the established send pipeline while adding required committed-file durability flush for `EJECT_SAFE` completion plus explicit OS safe-removal guidance; Iteration 67 adds the provider-neutral BookConverter capability contract and application-owned bounded/cancellable conversion lifecycle with atomic artifact registration; Iteration 68 adds an optional no-shell calibre `ebook-convert` adapter with safe discovery, bounded diagnostics, timeout/cancellation and sandboxed temporary input. Iteration 69 adds MP3/M4B import and an internal audiobook Reader with explicit multi-track grouping, chapters, speed/sleep controls, exact bookmarks and shared cross-device `audio:` progress anchors. Iteration 70 adds deterministic one-file-per-book Markdown export for Obsidian/Joplin over the existing bounded annotation projection, with optional frontmatter/backlinks and ownership-checked re-export that never silently overwrites unrelated notes. Iteration 71 adds Plugin API 1.3 with an optional provider-neutral AI extension point, host-owned per-book/content/network consent checks and SecretStore-only provider credential access; no AI provider is enabled by default. Iteration 72 is a release-hardening checkpoint: it restores the missing Reader golden ZIP, makes PDF raster tests explicitly headless, refreshes stale UI/E2E fixtures after audiobook/Continue Reading changes, and establishes a 1,035-test split full-regression baseline without claiming a newer monolithic reactor PASS. Iteration 73 hardens the external-acceptance handoff by refreshing the current validation state, adding a canonical six-gate runbook and a PR-CI readiness ratchet that remains fail-closed on missing harness/workflow contracts while never converting offline readiness into external PASS. Iteration 74 adds deterministic release-candidate integrity records that bind exact Git SHA + source-tree policy state + complete checksummed platform payload, and makes GitHub connected acceptance require the Windows integrity record before candidate staging. Iteration 75 carries that exact Windows integrity JSON, sidecar and release checksum manifest through digest-verified ingest into the immutable final reviewer ZIP, where they are independently revalidated against the final candidate evidence. Iteration 76 makes the reviewer-facing GitHub/final Markdown fail-closed canonical projections of the embedded JSON so human-readable status cannot diverge from machine-readable acceptance evidence. Iteration 77 centralizes exact evidence scenario/schema compatibility in one fail-closed registry that is itself candidate-bound, preventing producer/verifier schema drift and silent legacy/future evidence acceptance. Iteration 78 performs the final local preflight, revalidates the 1,035-test split baseline after bounded content-index executor hardening, closes the locally runnable check sweep, and proves the Linux production/portable packaging path while leaving the six live GitHub/Windows gates untouched. Iteration 79 responds to real Windows startup evidence: it explicitly binds Spring to the intended `ContentIndexingQueueService` production constructor after Iteration 78 introduced a second constructor for deterministic worker-pool testing, and adds a context-level regression so the missing-no-arg failure cannot recur unnoticed. Iteration 80 responds to the next real Windows startup trace by explicitly wiring `OpdsAccessTokenService`, adding an eager full-Spring-context smoke, removing `final` from two Spring-proxied repositories, and adding constructor/proxyability policy guards so runtime bean-creation defects are caught locally before another Windows handoff. Iteration 81 responds to subsequent real Windows use by enforcing per-format export resolution so `FB2_ZIP` cannot silently fall back to plain `FB2`, and by making the Reader TTS voice chooser render explicit human-readable labels instead of provider implementation-object identities. Iteration 82 responds to further real Windows workflow evidence by making voice discovery code-page independent via UTF-8 Base64 transport, treating successful mass export as a state-refresh/selection-consumption transaction that preserves Author Workspace context, and routing Annotations/Notes through Reader-safe cleanup before workspace loading. Iteration 83 responds to the next real Windows trace by making TTS discovery fail closed with encoded PowerShell commands, strict `MHLVOICE|` framing and separated stderr, and by moving the Annotation Manager table resize callback out of FXML into Java after JavaFX 21 rejected the symbolic callback string. The external acceptance items MHL-010/011/012/017/018/019 remain OPEN and require real Windows/GitHub evidence.

Historical checkpoint/task/continuation files were moved out of the repository root on 2026-09-12 to keep the source root code-focused. Their original filenames are preserved under `docs/history/records/` so cross-references inside the historical record remain readable.


---

## Iteration 84 WIP snapshot — 2026-09-14

**Original source:** `docs/history/ITERATION-84-WIP-SNAPSHOT-2026-09-14.md`

# MyHomeLib 8.0.0 — Iteration 84 WIP snapshot

Дата snapshot: 2026-09-14
Статус: робочий проміжний кандидат; фінальний regression/test cycle ще НЕ запускався.
База: Iteration 83 source від 2026-09-13.

## Що вже реалізовано

### 1. Reader: анотації та нотатки
- Повна Reader-модель анотації: type, quote, note text, tags, state/relocation metadata замість одного boolean note.
- Детермінований hit-test анотацій і активація мишею/клавіатурою.
- Пріоритет note-marker при перекритті діапазонів.
- Спільний AnnotationEditorDialog для create/edit: цитата, текст нотатки, колір, теги, validation.
- Reader annotation popover: edit, delete, copy quote, copy quote + note, reanchor/rebind.
- Явні стани resolved / relocated / artifact mismatch / unresolved.
- Безпечний artifact rebind: тільки при достатньо надійному quote/context match і після дії користувача.
- Annotation workflow винесено з великого NewReaderWorkspaceController в ReaderAnnotationCoordinator.

### 2. Reader UI/UX
- Єдина бічна панель: Зміст / Пошук / Закладки / Нотатки / Карта книги.
- Фільтрація анотацій за текстом, типом і тегами.
- Групування списку анотацій за главами.
- Book Map: прогрес + щільність notes/highlights/bookmarks по главах.
- Текстовий пошук у Reader може працювати в боковій панелі для звичайних текстових форматів.
- Основний Reader toolbar спрощено; рідкі команди перенесено в overflow `⋮`.
- Повернення фокусу в Reader після popover/dialog actions.
- Restore-position використовує semantic textOffset як джерело істини і переобчислює chapterIndex після reflow/змін документа.

### 3. Annotation Manager / knowledge workflow
- Узгоджена multi-selection семантика: Open/Edit — один запис; Delete/Color/Tags/Export — batch.
- Undo history до 20 логічних операцій; batch delete — одна undo-операція.
- Batch color, add/remove tags, selected export.
- Markdown «Конспект з анотацій» із групуванням книга → глава → цитата → нотатка → теги → MyHomeLib backlink; порядок відповідає позиції в книзі, а книги з однаковою назвою розрізняються за `bookId`.
- Digest export публікується атомарно через тимчасовий файл.

### 4. Main UI / search / library
- Main toolbar переведено на адаптивний однорядковий HBox.
- Selection actions винесено в контекстну панель, що з’являється лише при виборі книг.
- Pinned Smart Collection зберігається як явний scope під час text/advanced search.
- «Скинути область» прибирає scope без втрати введеного запиту/фільтрів.
- Unsupported content-index scope не маскується глобальними результатами.
- Book Details структуровано на Main / Reading / Files / Library / Technical.
- Follow Author UI чіткіше показує follow state і new-book count.

### 5. Масштаб / runtime / QA-підготовка
- Виправлено replacement-accounting у Reader ImageCache: заміна існуючого ключа більше не витісняє зайві сусідні зображення; статистичні/read-методи синхронізовані.
- CSV та selected-digest export у Annotation Manager переведено на temp + atomic move (із safe replace fallback), щоб cancel/error не залишав частково записаний фінальний файл.
- CSV-експорт нейтралізує spreadsheet formula markers (`=`, `+`, `-`, `@`) лише у вихідному CSV, не змінюючи дані нотаток у БД.
- Для повного INPX-каталогу >=100k записів дозволений bounded batch 5000 замість стандартного 1000.
- Доданий cancellation-контракт до початку bulk DB mutation.
- Runtime UTF-8 default-charset guard; Windows native.encoding лишається діагностикою, а не false failure.
- Доданий JavaFX CI gate через Xvfb та перевірка, що всі 3 *FxTest suites реально виконались.
- Доданий C.UTF-8 regression gate для Unicode path/export.
- Додані/оновлені acceptance/contract tests для annotation lifecycle, relocation ambiguity, hit-test, restore-position/reflow, UI structure.

### 6. Версія і локалізація
- Активну product/Maven версію уніфіковано на 8.0.0.
- Legacy v71-* entrypoints не перейменовуються для сумісності, але більше не позиціонуються як поточна версія продукту.
- Нові UI-ключі синхронізовано для UK/EN/BG; root Lang і bundled lang узгоджені.

## Важливо: що ще НЕ підтверджено

Цей snapshot створено ДО фінального тестового циклу за домовленістю. Тому цей архів не є release/PASS build.

Ще належить:
- завершити статичне оновлення застарілих UI/version regression-contracts;
- code freeze;
- один фінальний Maven/build/test cycle;
- JavaFX/Xvfb gate;
- Unicode/C.UTF-8 gate;
- великий INPX acceptance/performance run;
- Windows-specific TTS/FXML acceptance там, де потрібне Windows-середовище;
- виправити всі знайдені фінальним прогоном регресії;
- сформувати фінальний Iteration 84 source ZIP і test report.

## Поточний change-set проти Iteration 83

На момент підготовки snapshot: понад 100 змінених/нових файлів; окремий детальний changelog: `docs/release/ITERATION-84-CHANGELOG.md`.


## Додатково виправлено після первинного snapshot-pass

- Усунуто production FXML type mismatch: `MainView.fxml` використовує `HBox mainToolbar`, і `MainController` тепер теж очікує `HBox`, а не старий `FlowPane`.
- Прибрано застарілу залежність MainController від `themeButton`; зміна теми лишається доступною через overflow menu.
- Оновлено toolbar CSS з wrapping FlowPane contract на single-row adaptive HBox contract.
- Оновлено старі `MainToolbarLayoutContractTest`, `MainToolbarWrapFxTest`, `UiFxmlRegressionTest` і `search-toolbar-genre-followup-check.py`, щоб вони перевіряли новий UI, а не вимагали повернення старого.
- Статично звірено FXML wiring для Main/Annotations/Author/Details/Reader/Search: відсутніх handler-методів у змінених workspace FXML не знайдено.
- Статично звірено Maven identity: root + усі 15 модулів використовують/успадковують 8.0.0.
- Windows desktop acceptance report більше не друкує активний продукт як «7.1»: заголовок і JSON `projectVersion` беруть фактичну версію з root `pom.xml`; legacy script filenames лишаються сумісними.

- Додано acceptance-contract POST через повний `HttpOnlineBookDownloadAdapter` та redirect для валідного remote INPX.
- Оновлено `stage36-reader-annotations-check.py` під повну Iteration 84 annotation-модель/coordinator і `inpx-import-performance-check.py` під нову large-catalog batch policy.


---

## Dependency upgrade final validation — 2026-09-15

**Original source:** `DEPENDENCY-UPGRADE-FINAL-VALIDATION-2026-09-15.md`

# MyHomeLib 8.0.0 — Dependency Upgrade Final Validation

Validation date: 2026-09-15

## Upgraded dependency baseline

- Java: 21 (validated with JDK 21 toolchain; Windows preparation log used Temurin 21.0.11)
- Spring Boot: 4.1.1
- JavaFX: 21.0.12
- SQLite JDBC: 3.53.4.0
- Flyway Core: 12.4.0
- JUnit: 6.0.3
- Mockito: 5.23.0
- AssertJ: 3.27.7
- Testcontainers: 2.0.5
- ArchUnit: 1.5.0
- Maven Surefire: 3.5.6
- Bundled Maven: 3.9.6

The Spring Boot 4 migration includes package/configuration changes required by the new baseline (including Boot health contributor API and JDBC test auto-configuration package changes). The project continues to own its explicit multi-datasource Flyway lifecycle rather than delegating it to a generic Boot Flyway auto-configuration path.

## Windows offline repository provenance

The included `.mvn/repository` is the upgraded repository prepared on Windows from the project-provided `PREPARE-OFFLINE-REPO.cmd` / `tools/prepare-offline-repo.ps1` workflow.

That workflow creates `maven-offline-repo-upgraded.zip` only after the following sequence succeeds:

1. online `clean verify`;
2. dependency synchronization / `dependency:go-offline`;
3. offline `clean verify` against the project-local Maven repository;
4. cleanup of transient Maven cache markers;
5. repository ZIP creation.

The generated repository archive was then imported into this bundle and independently inspected for the required Spring Boot 4.1.1, JavaFX 21.0.12 Windows, SQLite JDBC 3.53.4.0 and Flyway 12.4.0 artifacts.

## Independent offline reactor validation

The upgraded project was independently verified offline using the bundled Maven 3.9.6 and the included repository.

Because the repository was populated on Windows, JavaFX 21.0.12 contains Windows native classifier JARs, not Linux native classifier JARs. On the Linux validation host, the reactor was therefore executed with the Windows JavaFX classifier only to validate compilation, dependency resolution, non-native logic, Spring wiring, database behavior, architecture and end-to-end tests.

Exactly two native-JavaFX-dependent test classes were excluded on Linux:

- `WorkspaceManagerNavigationStateTest` — 2 tests;
- `SpringContextStartupSmokeTest` — 1 test.

Those classes require a native JavaFX toolkit and cannot initialize on Linux from Windows JavaFX native JARs (`No toolkit found`). No other test classes were excluded.

Independent Linux offline result after those platform-only exclusions:

- reactor modules: 16/16 SUCCESS;
- tests executed: 1,094;
- failures: 0;
- errors: 0;
- skipped: 10;
- Maven result: BUILD SUCCESS;
- total reactor time: approximately 1 minute 36 seconds.

## Static / architecture / security validation

All of the following project gates passed on the final upgraded tree:

- dependency-upgrade-check;
- static release check;
- Iteration 85 finish/polish check;
- implementation completeness check;
- architecture check;
- functional regression check;
- XML/archive security check;
- privacy/temp lifecycle check;
- SecretStore policy check;
- managed executor check;
- Spring constructor wiring check;
- Spring proxyability check;
- supply-chain policy check;
- persistence error transparency check.

Selected metrics from the final gates:

- POM/FXML/XML files checked: 45, errors: 0;
- SQLite migrations: 60, integrity OK;
- production Java files checked by completeness gate: 1,095;
- Java sources seen by static release check: 1,502;
- test sources: 378;
- unsupported TODO/FIXME markers: 0;
- empty public/protected methods: 0;
- missing FXML handlers: 0;
- supply-chain policy: PASS.

## Real-data acceptance — FB2

The reader was exercised against both supplied real FB2 archives on the upgraded dependency stack.

Result:

- 2/2 books parsed successfully;
- failures: 0;
- errors: 0;
- Maven result: BUILD SUCCESS.

Probe 1:
- source bytes: 14,618,515;
- text characters: 6,657,913;
- paragraphs: 54,551;
- chapters: 16;
- TOC items: 316;
- resources: 41.

Probe 2:
- source bytes: 8,181,973;
- text characters: 3,470,361;
- paragraphs: 28,682;
- chapters: 9;
- TOC items: 172;
- resources: 38.

## Real-data acceptance — Flibusta INPX

The production INPX import path was exercised against the supplied `flibusta_online_fb2.inpx` on the upgraded SQLite/Flyway stack.

Input SHA-256:

`75bebb7a7ccf203bd934ef2af986f17d737ba4c4abfc277956f60bb84a6c7655`

Result:

- processed: 707,154 / 707,154;
- imported: 707,154;
- errors: 0;
- books in resulting catalog: 707,154;
- authors: 166,231;
- genres: 272;
- explicitly deleted records: 135,207;
- resulting SQLite database size: 1,583,337,472 bytes;
- duration: 92,609 ms;
- throughput: approximately 7,635.91 books/second;
- Maven result: BUILD SUCCESS.

## Offline verification on Windows

Prerequisite: JDK 21 available on `PATH`.

Recommended command:

```bat
VERIFY-OFFLINE-WINDOWS.cmd
```

Equivalent direct command:

```bat
mvnw.cmd -o -B -ntp clean verify
```

The wrapper launcher uses the bundled Maven 3.9.6 and the project-local `.mvn\repository`; no system Maven installation is required.

## Packaging scope

The full offline Windows x64 bundle includes:

- complete source tree;
- project Maven configuration;
- Maven 3.9.6 runtime;
- upgraded project-local Maven repository;
- Windows JavaFX 21.0.12 native classifier artifacts;
- Maven wrapper files and checksum;
- offline build/verification scripts;
- this validation report.

Generated `target` directories, temporary databases, test outputs, `dist`, Python caches, IDE metadata and VCS metadata are intentionally excluded from the final ZIP.

## Platform note

This bundle is self-contained for Maven dependency resolution on Windows x64, except for the required JDK 21 installation. It does not claim to contain Linux/macOS JavaFX native classifier artifacts. A Linux/macOS offline build must first add the matching JavaFX 21.0.12 platform classifiers to `.mvn/repository`.


---

## Post-audit fixes and validation — 2026-09-15

**Original source:** `POST-AUDIT-FIXES-AND-VALIDATION-2026-09-15.md`

# MyHomeLib 8.0.0 / Iteration 85 — post-audit fixes and validation

Date: 2026-09-15

## Implemented fixes

1. Added shared `ProcessExecutionSupport` with concurrent bounded stdout/stderr draining, timeout handling and descendant termination; migrated TTS, command-backed SecretStore and OS power probes to it.
2. Changed supported-platform power-probe failures to a conservative battery state so heavy indexing does not start when power state is unknown.
3. Moved long-running JavaFX command/batch/copy/export work off the FX thread and preserved UI-owned state through safe snapshots/callbacks.
4. Hardened OPDS/Web access: non-loopback OPDS requires authentication by default; web responses add `nosniff`, `DENY`, `no-referrer` and CSP/frame-ancestor protections.
5. Added configurable online download limits and free-space guards for both direct HTTP and ConnectionScript execution, including streamed/chunked overruns and partial-file cleanup.
6. Removed per-resource Reader `deleteOnExit()` use in favor of explicit session cleanup.
7. Added MCP SQLite schema-shape validation before queries.
8. Pinned GitHub Actions to immutable commit SHAs and added Dependabot for Maven and Actions.
9. Added JDK 21+ / Maven 3.9.6+ checks to build launchers.
10. Reduced `MainController` and `LuceneSearchService` below existing project ratchets by extracting focused services.
11. Updated release identity/documentation to the exact hardened Iteration 85 candidate.

## Final local validation

- Maven 3.9.6 / OpenJDK 21.0.11 / `C.UTF-8`, offline repository.
- Full `clean verify`: **16/16 reactor projects BUILD SUCCESS**.
- Tests: **1,097**, failures **0**, errors **0**, skipped **12**.
- Display-capable JavaFX: **8/8 tests**, **6/6 suites**, skipped **0**.
- Real INPX (`flibusta_online_fb2.inpx`): **707,154/707,154 imported**, errors **0**, 96,218 ms, 7,349.50 books/s.
- Real FB2 corpus: **2/2 supplied archives PASS**.
- Linux `jpackage` app-image smoke: PASS.
- Extracted Linux portable archive smoke: PASS.
- Release checksums: PASS, 3 artifacts.
- Stage 23 artifact validation with required portable/checksums: PASS.
- Architecture, implementation-completeness, security/static, supply-chain, startup, persistence, archive/XML, INPX and UI policy gates: PASS.

## External / deliberately unresolved release gates

The following are not claimed locally because they require external credentials, services, signing identities or Windows hosts:

- Windows TTS/DPAPI runtime acceptance;
- Windows DPI 100/125/150/200 visual acceptance;
- MSI/EXE install/update/uninstall under a standard Windows user;
- live GitHub required-check evidence;
- live SBOM/SCA/CodeQL evidence bound to the exact release SHA;
- installer/code signing and provenance tied to real signing credentials.

Dependency major/minor migration (Spring Boot/JavaFX/sqlite-jdbc) was not performed in the offline remediation pass because the supplied repository contains the candidate's current dependency set. Such upgrades must be done in a separate dependency-update branch with the new artifacts available and the same full validation matrix.


---

## Post-UX / Reader / Operations fixes — 2026-09-15

**Original source:** `POST-UX-READER-OPERATIONS-FIXES-2026-09-15.md`

# Post-UX / Reader / Operations fixes — 2026-09-15

This source snapshot includes the follow-up usability and format work requested after the Iteration 85 dependency upgrade.

## Reader

- Ordinary primary-button drag over rendered text creates a text selection; Shift+drag remains supported.
- Double-click and long-press select the nearest word.
- Selection context menu exposes Highlight, Note, Dictionary, Translation, Copy and Clear; existing annotation callbacks remain the persistence boundary.
- Added built-in Reader registration for DRM-free MOBI/PRC/AZW/AZW3 containers. Uncompressed and PalmDOC-compressed text are supported; DRM and HUFF/CDIC are rejected explicitly with a conversion hint instead of rendering corrupted text.
- Shared format/import/cover/maintenance conversion registries recognize PRC together with MOBI/AZW/AZW3; supported MOBI/AZW entries inside generic ZIP containers can also be selected by the Reader.

## UI/theme/localization

- The active-collection scope chip now uses theme panel/text/border tokens instead of a near-white accent derivation in dark themes.
- Localization formatting accepts both existing printf placeholders and MessageFormat placeholders, so `Колекція: {0}` now renders the actual collection name.

## Background operations

- The library-operation coordinator publishes active root-operation and terminal outcome events.
- Status bar shows the active background process with priority and progress where available, then restores the prior foreground status.
- Collection update/import actions fail early with a user-facing explanation of the operation that currently owns the library (for example, an index rebuild).
- The Operations workspace keeps up to 100 current-session records and shows running plus completed/cancelled/failed operations with type, stage, progress, start/end time, duration, result and error details.
- Automatic Lucene rebuild, collection switch and folder synchronization are bridged into the Operations history; classic imports publish rich structured progress directly. Book downloads and full-text content-index rebuilds now publish the same history/progress stream, including terminal failure/cancellation results.
## Final validation

- Final reactor validation cycle after these changes: 1,111 tests, 0 failures, 0 errors, 12 skipped.
- Reader, UI, OPDS, bootstrap, MCP, architecture and E2E modules completed with BUILD SUCCESS.
- The Linux verification environment used the Windows JavaFX 21.0.12 classifier from the prepared offline repository, so `WorkspaceManagerNavigationStateTest` and `SpringContextStartupSmokeTest` were excluded from this Linux run because they require a native JavaFX toolkit. Their exclusion is platform-specific, not a functional pass claim.
- The project-only archive intentionally excludes bundled Maven binaries, the local Maven repository, target/dist directories and generated test output.


---

## Iteration 84 final

**Original source:** `ITERATION-84-FINAL.md`

# MyHomeLib 8.0.0 — Iteration 84 FINAL

Дата локальної фінальної валідації: 2026-09-14
Статус: **FINAL source candidate; local Linux validation PASS**.

Iteration 84 завершує поглиблений аудит Iteration 83 і фокусується на завершеності користувацьких workflow, насамперед Reader annotations/notes, Reader UI, Annotation Manager, Smart Collection scope, restore-position, великому INPX та release/QA gates.

## Головне, що зроблено

- Reader отримує повну модель анотації (type, quote, note, tags, state), має hit-test, mouse/keyboard activation, popover, edit/delete/copy/reanchor/rebind.
- Додано спільний `AnnotationEditorDialog` і винесено orchestration в `ReaderAnnotationCoordinator`.
- Reader має єдину бічну панель: Зміст, Пошук, Закладки, Нотатки/підсвітки, Карта книги.
- Annotation Manager підтримує коректний multi-select, batch delete/color/tags/export і bounded Undo до 20 логічних операцій.
- Додано Markdown «Конспект з анотацій» з порядком читання, групуванням за книгою/главою та MyHomeLib backlinks.
- Smart Collection зберігається як явний search scope; unsupported content scope більше не підміняється глобальними результатами.
- Main toolbar стала однорядковою адаптивною; selection actions контекстні; Book Details структуровано за секціями; Follow Author показує state/new count.
- Restore position використовує semantic `textOffset` як джерело істини після reflow/document change.
- Reader `ImageCache` виправлено для replacement-accounting та thread-visible reads/statistics.
- CSV/digest export публікуються через temp + atomic move; CSV нейтралізує spreadsheet formula markers.
- Large full-snapshot INPX >=100k використовує bounded batch 5000; cancellation до bulk mutation покрито контрактом.
- Активна product/Maven identity уніфікована на **8.0.0**; legacy `v71-*` entrypoints залишені лише для сумісності.
- JavaFX CI gate тепер реально виконує `*FxTest` через Xvfb, а не приймає zero-test green result.

## Фінальна локальна валідація

- `mvn clean verify`: **1080 тестів**, 0 failures, 0 errors, 11 skipped — BUILD SUCCESS.
- JavaFX/Xvfb: **5 тестів у 3 Fx suites**, 0 failures/errors/skipped.
- Static/release gates: **21/21 PASS**.
- FXML: 29 workspaces, 256 handler references, 0 missing.
- SQLite: 60 migrations, integrity OK.
- Real INPX: **707154/707154** records, 0 errors, ~78.75 s, ~8979.73 books/s.
- Real FB2 corpus: 2 archives, 0 failures; ~874.9 ms (14.6 MB) and ~467.0 ms (8.2 MB).

Повний звіт: `docs/release/ITERATION-84-TEST-REPORT.md`.

## Межа підтвердження

Локальний Linux/Java 21 цикл зелений. Реальні Windows-only release gates (Windows TTS, DPI matrix, standard-user MSI/EXE/portable install/update/uninstall, live GitHub/SBOM/SCA/CodeQL evidence) не симулюються і залишаються зовнішніми acceptance gates.


---

## Iteration 84 changelog

**Original source:** `docs/release/ITERATION-84-CHANGELOG.md`

# MyHomeLib 8.0.0 — Iteration 84 changelog

Date: 2026-09-14
Status: working candidate; final regression pending

## Reader annotations and notes

- Reader receives complete annotation data instead of a `note=true/false` flag: type, quote, note text, tags, chapter/state and update metadata.
- Added deterministic annotation hit-testing and mouse/keyboard activation with note-marker priority for overlapping ranges.
- Added a reusable annotation editor for create/edit with quote preview, note text, color, tags and validation.
- Added Reader annotation popover actions: edit, delete, copy quote, copy quote + note, reanchor and safe artifact rebind.
- Artifact mismatch no longer silently hides or rewrites an annotation: exact/strong quote-context matching produces an explicit rebind candidate; ambiguous matches remain unresolved.
- Extracted Reader annotation orchestration from `NewReaderWorkspaceController` into `ReaderAnnotationCoordinator`.

## Reader UI/UX

- Added a single Reader side panel with Contents, Search, Bookmarks, Annotations and Book Map.
- Annotation sidebar supports text search, type/tag filtering, chapter grouping, issue visibility and navigation to the source location.
- Book Map summarizes chapter progress plus note/highlight/bookmark density.
- Text-format search can run inside the side panel; specialized PDF/audio/comic behavior remains format-specific where appropriate.
- Restore position now treats semantic `textOffset` as authoritative and recalculates the chapter index after reflow/document changes.

## Annotation Manager and knowledge workflow

- Multi-selection semantics are consistent: Open/Edit are single-item actions; Delete/Color/Tags/Export are batch actions.
- Added bounded undo history (20 logical operations), including batch delete as one undo step.
- Added batch color and tag operations plus selected-row export.
- Added “Annotation digest” Markdown export grouped by book and chapter with quote, note, tags and MyHomeLib backlink; export order follows reading offsets/positions and selected digests distinguish books by `bookId` even when titles match.

## Library/search UI

- Pinned Smart Collection remains an explicit search scope while text/advanced filters are refined.
- Unsupported content-index scoping is reported explicitly instead of returning misleading global matches.
- Main toolbar is split into persistent navigation/search actions and contextual selection actions.
- Book Details is grouped into Main, Reading, Files, Library and Technical sections.
- Follow Author UI exposes current follow state and new-book count more clearly.

## Scale, runtime and QA

- Fixed Reader `ImageCache` replacement accounting so replacing an existing image reserves only the final required budget instead of evicting unrelated cached images; cache statistics/read helpers are synchronized for cross-thread visibility.
- Annotation Manager CSV and selected-digest exports now publish through temporary files with atomic move when supported (safe replace fallback otherwise), so cancellation/errors cannot leave a partially written final export.
- Annotation CSV output neutralizes spreadsheet formula markers (`=`, `+`, `-`, `@`) in exported cells without mutating stored annotation data.
- Full-catalog INPX import may use a bounded 5,000-record batch when the incoming catalogue is at least 100,000 records.
- Added runtime UTF-8 default-charset diagnostics without treating Windows `native.encoding` as a false release failure.
- Added a display-capable JavaFX CI gate through Xvfb and a guard requiring actual FX tests to execute.
- Added/updated contracts for relocation ambiguity, hit-testing, Reader position/reflow, annotation workflow and Iteration 84 UI structure.
- Online acceptance coverage now includes ConnectionScript POST through the full book adapter and HTTP redirect to a validated remote INPX catalog.
- Updated legacy static gates so they validate the Iteration 84 annotation DTO/coordinator design and large-catalog batch policy instead of obsolete Iteration 83 source shapes.

## Release identity

- Product/Maven active version is unified at **8.0.0**.
- Legacy `v71-*` external-acceptance script names and schema identifiers remain compatibility-stable; they are no longer presented as the current product version.

## Validation status

Final local validation on 2026-09-14 is **PASS**:

- full offline `mvn clean verify`: 1080 tests, 0 failures, 0 errors, 11 skipped;
- JavaFX/Xvfb: 5 tests in 3 Fx suites, 0 failures/errors/skipped;
- static/release gates: 21/21 PASS;
- real INPX: 707154/707154, 0 errors;
- real FB2 corpus: 2 archives, 0 failures.

See `docs/release/ITERATION-84-TEST-REPORT.md` for evidence and the explicit Windows/live external acceptance boundary.


---

## Iteration 84 test report

**Original source:** `docs/release/ITERATION-84-TEST-REPORT.md`

# MyHomeLib 8.0.0 — Iteration 84 final validation report

**Дата:** 2026-09-14  
**Candidate:** MyHomeLib 8.0.0 / Iteration 84  
**Локальний статус:** **PASS**  
**Середовище:** Linux, OpenJDK 21.0.11, Maven 3.9.6, `C.UTF-8`, offline Maven repository.

## 1. Full Maven verification

Команда класу: offline `mvn clean verify` по всьому 16-project reactor.

**Результат:** `BUILD SUCCESS`.

| Модуль | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| myhomelib-shared | 19 | 0 | 0 | 0 |
| myhomelib-domain | 24 | 0 | 0 | 0 |
| myhomelib-application | 294 | 0 | 0 | 1 |
| myhomelib-plugin-api | 28 | 0 | 0 | 0 |
| myhomelib-plugin-samples | 2 | 0 | 0 | 0 |
| myhomelib-infrastructure | 441 | 0 | 0 | 6 |
| myhomelib-reader | 83 | 0 | 0 | 1 |
| myhomelib-ui | 107 | 0 | 0 | 0 |
| myhomelib-web | 6 | 0 | 0 | 0 |
| myhomelib-opds | 20 | 0 | 0 | 0 |
| myhomelib-bootstrap | 18 | 0 | 0 | 0 |
| myhomelib-mcp | 6 | 0 | 0 | 0 |
| myhomelib-architecture-tests | 14 | 0 | 0 | 0 |
| myhomelib-e2e-tests | 15 | 0 | 0 | 0 |
| myhomelib-benchmark | 3 | 0 | 0 | 3 |
| **Разом** | **1080** | **0** | **0** | **11** |

Skipped-тести — opt-in performance/environment probes (real INPX/FB2/Lucene/statistics/author search), Windows DPAPI integration та benchmark guardrails. Критичні real INPX і real FB2 probes були запущені окремо й пройшли — див. нижче.

## 2. Display-capable JavaFX gate

Після виправлення Surefire/headless policy JavaFX gate запускався через Xvfb із display-capable JVM.

**Результат:** `FX_TEST_GATE_OK: tests=5, skipped=0, suites=3`.

- `AnnotationManagerFxmlFxTest`: 1
- `MainLayoutServiceFxTest`: 3
- `MainToolbarWrapFxTest`: 1

**Failures/Errors:** 0/0.

Це підтверджує, що UI gate більше не є zero-test false green.

## 3. Static / release gates

Виконано **21/21** незалежних gate-команд, усі завершилися `EXIT=0`.

Ключові результати:

- Critical UI localization: PASS — 597 stable keys / 20 source files.
- Architecture baseline: PASS; UI debt ratchet покращено до 15/18 output-port users і 24/28 non-value domain-model users.
- Implementation completeness: PASS; 0 TODO/FIXME/unsupported markers, 0 empty public/protected methods, 0 exact large cross-file clones.
- Functional regression: PASS — 29 FXML, 238 retained bindings, 454 retained ids, 31 critical behavior ratchets.
- Reader refactor: PASS — `ReaderCanvas` 940 lines; selection/history and overlay painting extracted.
- Language catalogues: PASS — UK/EN/BG, по 1031 UI keys + 335 genre keys.
- Annotation stages 35/36/37: PASS.
- INPX hot-path/search-index consistency: PASS.
- Online runtime/download/rollback: PASS.
- XML/archive security: PASS.
- Privacy/temp lifecycle: PASS.
- Managed executors / Spring wiring / proxyability / startup orchestration: PASS.
- Offline static release check: PASS — 29 FXML workspaces, 256 handler references, 0 missing; 60 SQLite migrations, integrity OK; 10 root shell scripts, 0 static issues.

## 4. Real INPX acceptance — user supplied Flibusta index

Файл: `flibusta_online_fb2.inpx`.

Production pipeline, full snapshot, batch 5000:

- records processed/imported: **707154 / 707154**;
- errors: **0**;
- transaction commit: **78179 ms**;
- total import duration: **78750 ms**;
- throughput: **8979.73 books/s**;
- books: 707154;
- authors: 166231;
- book-author links: 856591;
- genres: 272;
- book-genre links: 1102219;
- catalog state rows: 707154;
- DB size: 1,584,816,128 bytes.

**Result:** PASS.

## 5. Real FB2 Reader acceptance

Запущено opt-in `RealFb2ReaderProbeTest` на двох наданих користувачем FB2 ZIP archives.

### Corpus item 1

- compressed FB2 entry: 14,618,515 bytes;
- text characters: 6,657,913;
- paragraphs: 54,551;
- chapters: 16;
- TOC entries: 316;
- resources: 41;
- parse: **874.9 ms**.

### Corpus item 2

- compressed FB2 entry: 8,181,973 bytes;
- text characters: 3,470,361;
- paragraphs: 28,682;
- chapters: 9;
- TOC entries: 172;
- resources: 38;
- parse: **467.0 ms**.

All title/text/chapter/offset/TOC invariants passed.  
**Result:** PASS.

## 6. Important regressions found and fixed during final validation

Final validation was not only confirmatory. It found and fixed:

- Unix ASCII filesystem/native encoding handling in `RuntimeEncodingGuard` while avoiding false failures on Windows native code pages.
- Stale User-Agent test expecting 7.1 after production identity moved to 8.0.0.
- Missing offline PDFBox/JBIG2 test dependencies in the local validation repository (test environment only; source unchanged).
- Stale UI source-contracts after `ReaderAnnotationCoordinator` extraction.
- Missing accessible label for annotation issue control.
- Surefire `java.awt.headless=true` configuration that previously prevented Xvfb Fx tests from actually running.
- Critical UI localization/architecture/completeness/refactor gate findings without weakening the gates.
- A Smart Collection accessor regression introduced during architecture cleanup.

After remediation, the entire Maven reactor and independent gates were rerun successfully.

## 7. Remaining external gates

The following are **not claimed PASS** because this environment is Linux/offline and cannot provide honest external evidence:

- live GitHub required-check enforcement / PR CI evidence;
- Windows DPI 100/125/150/200 screenshot acceptance;
- real Windows standard-user MSI/EXE/portable install/update/uninstall/desktop smoke;
- Windows native TTS/DPAPI paths not executable on Linux;
- live release SBOM publication evidence;
- live SCA/vulnerability report under release policy;
- live exact-candidate CodeQL/SAST evidence.

These remain external release gates and must not be inferred from the local green build.

## Final conclusion

**Iteration 84 source is locally validated and ready as the final source candidate.**  
All executable local gates available in this environment are green. Windows/live-service acceptance remains explicitly external.


---

## Iteration 85 final

**Original source:** `ITERATION-85-FINAL.md`

# MyHomeLib 8.0.0 — Iteration 85 FINAL

Iteration 85 closes the local Finish/Polish roadmap that remained after Iteration 84.

## Scope closed

- Book-list activity badges/counters for notes, highlights and bookmarks.
- Library filter by annotation presence: notes, highlights, or either.
- Consistent SQLite and Lucene semantics for annotation-presence filtering.
- Bulk page activity loading without N+1 queries.
- Selective Lucene refresh after annotation changes and restore flows.
- Lucene schema bump for derived activity flags with rebuild of stale indexes.
- Expanded display-capable JavaFX acceptance for Reader sidebar, book activity column and filter dialog.
- Reproducible Reader JFR/heap profiling for 20/50/100 MB FB2 and EPUB fixtures.
- Final regression on the user-supplied 707,154-record INPX and two large FB2 archives.

## Post-audit hardening — 2026-09-15

The final local candidate additionally closes high-impact findings from an independent source audit:

- native-process stdout/stderr are drained concurrently with bounded capture and process-tree timeout termination;
- supported-platform power-state probe failures fail conservatively instead of enabling heavy indexing;
- long-running JavaFX actions moved off the FX event thread with immutable/safe UI snapshots where needed;
- non-loopback OPDS is authenticated by default and browser-facing responses include hardening headers;
- direct and ConnectionScript downloads enforce byte limits and free-space guards;
- Reader temporary-resource cleanup no longer relies on per-file `deleteOnExit()` registration;
- MCP validates required SQLite schema shape before serving requests;
- GitHub Actions are pinned to immutable commit SHAs and Dependabot tracks Maven/Actions updates;
- build launchers enforce JDK 21+ and Maven 3.9.6+;
- large orchestration classes were reduced to remain within the project ratchets (`MainController` 674/680, `LuceneSearchService` 439/460).

## Local final status

- Maven `clean verify`: **1097 tests, 0 failures, 0 errors, 12 skipped**.
- JavaFX/Xvfb: **8/8 tests, 6/6 suites, 0 skipped**.
- Static/release gates: **PASS**.
- Reader JFR/heap 20/50/100 MB: **PASS**.
- Real INPX: **707154/707154, 0 errors**.
- Real FB2 corpus: **2/2 PASS**.
- Linux portable package, extracted smoke, checksums and Stage 23 artifact validation: **PASS**.

The original locally executable Iteration 83 audit roadmap is now closed. Windows-native and live-service release evidence remains external by design; see `docs/release/ITERATION-85-TEST-REPORT.md`.


---

## Iteration 85 changelog

**Original source:** `docs/release/ITERATION-85-CHANGELOG.md`

# Iteration 85 changelog — Finish / Polish

**Product:** MyHomeLib 8.0.0  
**Iteration:** 85  
**Date:** 2026-09-15

Iteration 85 closes the remaining local roadmap items after Iteration 84. It intentionally does not introduce another large subsystem.

## 1. Book activity in the catalogue

- Added per-book note, highlight and bookmark counts to the catalogue view model.
- Added the activity column to the book table with localized labels/tooltips.
- Activity for a visible page is loaded in bulk instead of issuing an N+1 query per book.
- Added `BookActivityService`, `BookActivitySummary` and `BookActivityQueryPort` plus the SQLite adapter.

## 2. Filter books by annotations

Added `BookAnnotationPresenceFilter` with:

- Any;
- Notes;
- Highlights;
- Notes or highlights.

The filter is persisted in `BookFilterStateService`, exposed in the JavaFX filter dialog and implemented consistently for SQLite and Lucene.

## 3. Search-index consistency

- Lucene documents now contain derived `has_note`, `has_highlight` and `has_annotation` flags.
- Lucene schema marker advanced to `custom-fields-v2-activity`, forcing stale indexes to rebuild instead of silently missing activity fields.
- Annotation create/edit/delete flows refresh only affected books through `BookSearchIndexRefreshService`.
- Backup/restore paths mark restored annotation books for selective reindex.

## 4. UI acceptance

Expanded the display-capable JavaFX gate to six suites / eight tests, including:

- Annotation Manager FXML;
- Reader sidebar;
- book-table activity column;
- book-filter dialog;
- main layout;
- adaptive main toolbar.

The CI gate now requires both minimum test count and minimum suite count so a partial JavaFX run cannot be reported as green.

## 5. Reader memory / JFR profiling

Added reproducible 20/50/100 MB FB2+EPUB fixtures and a JFR profiling script. The benchmark measures:

- FB2 parse time;
- EPUB parse time;
- peak heap delta;
- retained heap after 20 open/close cycles;
- GC collection count.

All configured guardrails passed on the final candidate.

## 6. Acceptance / regression additions

- Added SQLite integration coverage for bulk activity counts.
- Added filter-state and SQL/Lucene annotation-presence contracts.
- Re-ran the production INPX pipeline on the user-supplied 707,154-record Flibusta index.
- Re-ran the streaming Reader on both supplied large FB2 archives.

## Change size versus Iteration 84

- **19 new files**;
- **32 changed files**;
- **0 deleted files**;
- **51 file-level differences** total, excluding build artifacts. This count includes the final Iteration 85 release documentation itself.

## Final local status

See `ITERATION-85-TEST-REPORT.md`. All locally executable roadmap gates are green. Windows-native installer/TTS/DPAPI/DPI and live GitHub/SBOM/SCA/CodeQL evidence remain external release gates.


## 7. Post-audit hardening

Independent technical review after the original Iteration 85 closure produced a focused hardening pass:

- centralized bounded native-process execution with concurrent stdout/stderr draining;
- conservative power-state fallback;
- asynchronous UI work for command testing/export/collection and annotation batch paths;
- OPDS LAN authentication-by-default plus browser hardening headers;
- online download size/free-space safeguards in both direct HTTP and ConnectionScript paths;
- explicit Reader temporary-resource lifecycle;
- MCP schema-shape fail-fast validation;
- immutable GitHub Actions SHA pins and Dependabot configuration;
- JDK/Maven build-tool minimum-version enforcement;
- additional UI/search orchestration extraction to satisfy project size ratchets.

The hardened candidate was revalidated on 2026-09-15 with 1,097 Maven tests, the display-capable JavaFX gate, the complete 707,154-record INPX import, both supplied real FB2 archives, and Linux portable packaging/release-artifact validation.


---

## Iteration 85 Reader memory/JFR note

**Original source:** `docs/release/ITERATION-85-READER-MEMORY-JFR.md`

# Iteration 85 — Reader memory / JFR report

**Date:** 2026-09-14  
**Environment:** Linux / OpenJDK 21.0.11

The Reader benchmark generates real synthetic FB2 and EPUB fixtures at 20, 50 and 100 MB and records a Java Flight Recording for each run. Each size also performs 20 open/close cycles to observe retained heap.

| Fixture | FB2 parse, ms | EPUB parse, ms | Peak heap delta | Retained after 20 open/close | GC collections |
|---:|---:|---:|---:|---:|---:|
| 20 MB | 313.2 | 349.7 | 79.3 MB | 0.0 MB | 126 |
| 50 MB | 606.0 | 757.9 | 183.6 MB | 0.0 MB | 319 |
| 100 MB | 1107.0 | 1107.7 | 497.6 MB | 0.0 MB | 397 |

Configured guardrails:

- parse < 15 s;
- peak heap delta < 768 MB;
- retained after 20 open/close < 256 MB.

**Result: PASS at 20, 50 and 100 MB.**

The raw `.jfr` recordings are distributed separately from the source archive so the source package stays clean and reproducible.


---

## Iteration 85 test report

**Original source:** `docs/release/ITERATION-85-TEST-REPORT.md`

# MyHomeLib 8.0.0 — Iteration 85 final validation report

**Date:** 2026-09-15  
**Candidate:** MyHomeLib 8.0.0 / Iteration 85  
**Local status:** **PASS**  
**Environment:** Linux, OpenJDK 21.0.11, Maven 3.9.6, `C.UTF-8`, offline Maven repository.

## 1. Full Maven verification

Final offline `mvn clean verify` completed with `BUILD SUCCESS` across the 16-project reactor.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| myhomelib-shared | 22 | 0 | 0 | 0 |
| myhomelib-domain | 24 | 0 | 0 | 0 |
| myhomelib-application | 299 | 0 | 0 | 1 |
| myhomelib-plugin-api | 28 | 0 | 0 | 0 |
| myhomelib-plugin-samples | 2 | 0 | 0 | 0 |
| myhomelib-infrastructure | 448 | 0 | 0 | 7 |
| myhomelib-reader | 83 | 0 | 0 | 1 |
| myhomelib-ui | 108 | 0 | 0 | 0 |
| myhomelib-web | 6 | 0 | 0 | 0 |
| myhomelib-opds | 20 | 0 | 0 | 0 |
| myhomelib-bootstrap | 18 | 0 | 0 | 0 |
| myhomelib-mcp | 7 | 0 | 0 | 0 |
| myhomelib-architecture-tests | 14 | 0 | 0 | 0 |
| myhomelib-e2e-tests | 15 | 0 | 0 | 0 |
| myhomelib-benchmark | 3 | 0 | 0 | 3 |
| **Total** | **1097** | **0** | **0** | **12** |

Skipped tests are opt-in/environment-specific probes. The critical real-file probes were executed separately below.

## 2. Display-capable JavaFX acceptance

Final Xvfb run:

`FX_TEST_GATE_OK: tests=8, skipped=0, suites=6`

Suites:

- `AnnotationManagerFxmlFxTest`: 1;
- `BookFilterDialogFxTest`: 1;
- `ReaderWorkspaceSidebarFxTest`: 1;
- `MainLayoutServiceFxTest`: 3;
- `MainToolbarWrapFxTest`: 1;
- `BookTableActivityFxTest`: 1.

**Failures / errors:** 0 / 0.

## 3. Static / release gates

The Iteration 85 finish/polish gate and the full static/release gate set passed. Final key recheck:

- `ITERATION85_FINISH_POLISH_CHECK_OK`;
- Spring proxyability: PASS — 246 final production classes inspected;
- offline static release check: PASS;
- XML/POM/FXML: 45, errors 0;
- FXML workspaces: 29; handler references: 256; missing: 0;
- SQLite migrations: 60; integrity: OK;
- root shell scripts: 10; static issues: 0;
- release packaging/CI static issues: 0;
- Java sources: 1502;
- test sources: 378.

The broader static suite also passed localization, architecture, implementation-completeness, functional-regression, Reader refactor, annotation, INPX, online-update, XML/archive-security, privacy/temp-lifecycle, managed-executor, Spring wiring/proxyability and startup-orchestration gates.

## 4. Reader JFR / heap profile

Reproducible synthetic FB2 and EPUB fixtures were generated at 20, 50 and 100 MB. Each size included 20 open/close cycles.

| Fixture | FB2 parse | EPUB parse | Peak heap delta | Retained after 20 open/close | GC collections |
|---:|---:|---:|---:|---:|---:|
| 20 MB | 313.2 ms | 349.7 ms | 79.3 MB | 0.0 MB | 126 |
| 50 MB | 606.0 ms | 757.9 ms | 183.6 MB | 0.0 MB | 319 |
| 100 MB | 1107.0 ms | 1107.7 ms | 497.6 MB | 0.0 MB | 397 |

Guardrails:

- parse time < 15 s;
- peak heap delta < 768 MB;
- retained heap after 20 open/close < 256 MB.

**Result: PASS for all three sizes.**

## 5. Real INPX acceptance

Input: user-supplied `flibusta_online_fb2.inpx`.

Production full-snapshot pipeline, batch 5000:

- records: **707154**;
- processed/imported: **707154 / 707154**;
- errors: **0**;
- total duration: **96218 ms**;
- throughput: **7349.50 books/s**;
- books: 707154;
- authors: 166231;
- book-author links: 856591;
- genres: 272;
- book-genre links: 1102219;
- catalogue-state rows: 707154;
- deleted-state rows: 135207;
- DB size: 1,583,419,392 bytes.

Input SHA-256: `75bebb7a7ccf203bd934ef2af986f17d737ba4c4abfc277956f60bb84a6c7655`.

**Result: PASS.**

## 6. Real FB2 Reader acceptance

Both user-supplied large FB2 ZIP archives passed the streaming Reader probe.

### Corpus item 1

- compressed FB2 entry: 14,618,515 bytes;
- text characters: 6,657,913;
- paragraphs: 54,551;
- chapters: 16;
- TOC entries: 316;
- resources: 41;
- parse: **854.3 ms**.

### Corpus item 2

- compressed FB2 entry: 8,181,973 bytes;
- text characters: 3,470,361;
- paragraphs: 28,682;
- chapters: 9;
- TOC entries: 172;
- resources: 38;
- parse: **467.7 ms**.

All title/text/chapter/offset/TOC invariants passed.  
**Result: PASS.**

## 6A. Post-audit hardening validation

Additional regression evidence on the hardened candidate:

- process execution deadlock/timeout regressions: PASS;
- OPDS non-loopback anonymous-access regression and auth challenge behavior: PASS;
- bounded HTTP/ConnectionScript download regressions: PASS;
- content-index queue/power-state regressions: PASS;
- MCP schema/WAL read-only compatibility regressions: PASS;
- architecture ratchets: PASS (`MainController` 674/680; `LuceneSearchService` 439/460);
- supply-chain policy: PASS with immutable GitHub Actions SHA pins;
- Linux `jpackage` app-image + portable archive smoke: PASS;
- `SHA256SUMS`: 3 release artifacts verified;
- `stage23-cross-platform-release-check.py --require-checksums --require-portable`: PASS.

The evidence-bundle verifier regression suite also passed. Its live immutable reviewer bundle remains an external acceptance input and is not fabricated locally.

## 7. Roadmap closure

The remaining local items from the original audit are now implemented and validated:

- book-level notes/highlights/bookmarks activity indication;
- books-with-notes / books-with-highlights filtering;
- fuller display-capable UI acceptance;
- 20/50/100 MB Reader JFR/heap profiling.

Together with Iteration 84, this closes the locally executable P0/P1/P2 roadmap identified in the Iteration 83 audit.

## 8. Remaining external release gates

These are not claimed PASS because this environment is Linux/offline:

- Windows-native TTS and DPAPI runtime paths;
- Windows DPI 100/125/150/200 visual acceptance;
- MSI/EXE/portable install, update, uninstall and desktop smoke as a standard Windows user;
- live GitHub required-check enforcement / PR evidence;
- live SBOM publication evidence;
- live SCA/vulnerability report;
- live exact-candidate CodeQL/SAST evidence.

These are external release acceptance, not unfinished local application functionality.

## Final conclusion

**Iteration 85 closes the local Finish/Polish roadmap and is validated as the final local source candidate.**


---

## Superseded external test plan — Iteration 78

**Original source:** `docs/release/EXTERNAL-TEST-PLAN-ITERATION-78.md`

# MyHomeLib — External test plan after Iteration 78

Date: 2026-09-13

Purpose: close only the six gates that cannot be proven locally. The local source is `READY_FOR_LIVE_EVIDENCE`; this plan starts after the Iteration 78 source candidate is frozen in Git.

## 0. Candidate freeze — release owner

**Environment:** Git repository + release owner workstation.

1. Use the formal release identity from `pom.xml` (currently `8.0.0`); changing it invalidates candidate-bound evidence.
2. Commit all approved changes, including the version decision.
3. Record the exact candidate Git SHA.
4. Run `python3 tools/external-acceptance-readiness.py --run-regressions` and require `READY_FOR_LIVE_EVIDENCE` with all six gates still `OPEN_EXTERNAL`.
5. Do not amend/rebase/rebuild the candidate after external evidence collection starts.

**PASS evidence:** candidate SHA, formal version, readiness JSON/Markdown.

**Reset rule:** any source/version/build-policy change after freeze creates a new candidate SHA and invalidates all candidate-bound evidence collected for the old SHA.

## 1. MHL-010 — GitHub PR CI enforcement and runtime

**Owner:** DevOps / repository administrator.  
**Environment:** real GitHub repository.

1. Confirm repository rules/branch protection require the **Fast gate** status check.
2. Obtain at least 5 successful pull-request Fast-gate samples as required by connected acceptance.
3. Confirm median Fast-gate runtime is <= 600 seconds.
4. Run/dispatch the connected acceptance workflow for the exact candidate SHA.

**PASS:** required check is enforced, sample policy passes, exact candidate SHA is accepted by GitHub connected acceptance.

**Save:** repository/rules evidence, workflow run IDs, GitHub connected acceptance JSON/Markdown.

## 2. MHL-017 — live SBOM

**Owner:** DevOps/Security.  
**Environment:** GitHub CI Release for the same candidate SHA.

1. Run `.github/workflows/ci-release.yml` on the exact candidate.
2. Require successful CycloneDX generation.
3. Verify `bom.json` and `bom.xml` are present in `myhomelib-supply-chain`.
4. Preserve the GitHub Actions artifact digest.

**PASS:** both SBOM formats are present, non-empty, generated by the successful exact-candidate release run, and the artifact digest verifies.

## 3. MHL-018 — dependency vulnerability scan

**Owner:** Security / DevOps.  
**Environment:** the same CI Release.

1. Require Dependency-Check JSON/SARIF/HTML artifacts.
2. Confirm the configured CVSS >= 7.0 blocking policy was active.
3. Review suppressions; every active suppression must have justification and expiry according to repository policy.

**PASS:** CI release is not bypassed, no prohibited vulnerability remains, reports are preserved in the exact-candidate supply-chain artifact.

## 4. MHL-019 — CodeQL / SAST

**Owner:** Security.  
**Environment:** GitHub CodeQL + the same candidate SHA.

1. Require a successful CodeQL analysis for the exact candidate.
2. Confirm the CodeQL release-gate record is in the supply-chain artifact.
3. Confirm there are no open blocking High/Critical alerts under the release policy.

**PASS:** candidate-bound CodeQL gate passes and GitHub connected acceptance accepts it.

## 5. Candidate artifact handoff to Windows

**Owner:** DevOps + Windows QA.  
**Environment:** real Windows machine, standard/non-elevated account, exact candidate checkout.

Use:

```powershell
.\tools\v71-windows-acceptance-start.ps1 `
  -Repository "OWNER/REPO" `
  -AcceptanceRunId <GITHUB_CONNECTED_ACCEPTANCE_RUN_ID> `
  -PreviousMsi <PATH_TO_REAL_PREVIOUS_RELEASE_MSI> `
  -PreviousVersion <REAL_PREVIOUS_VERSION>
```

The script must re-download the connected-acceptance artifact via GitHub, verify the GitHub artifact digest, verify `acceptance-harness.sha256`, preserve `release-candidate-integrity-windows.json` + sidecar + release `SHA256SUMS`, and create one bound Windows host/user/session record.

**Do not use a manually copied/repacked candidate ZIP for final PASS.**

## 6. MHL-012 — Windows packaging lifecycle

**Owner:** Windows QA.  
**Environment:** same bound host/user/session throughout.

Run and record:

1. MSI install as standard user where supported by the packaged mode.
2. First start.
3. Upgrade from the supplied **real previous-release MSI**.
4. Verify user data/settings/collections survive the upgrade.
5. Uninstall and verify documented user-data behavior.
6. Candidate EXE desktop launch/smoke.
7. Portable extraction to a Unicode path as ordinary user.
8. Verify portable data stays inside the portable tree and does not leak to the normal user profile/CWD.

**PASS:** all scripted/manual rows pass on the same candidate/host/user/session; no P0/P1 defect remains.

## 7. MHL-011 — Windows DPI acceptance

**Owner:** Windows QA / Frontend.  
**Environment:** same bound host/user/session and exact candidate.

At each Windows scale **100%, 125%, 150%, 200%**, capture real screenshots of:

- main window / navigation;
- import flow;
- search workspace/results;
- book details;
- Reader (text plus PDF/comic where required by the harness);
- dialogs/settings relevant to release acceptance.

Check for clipping, overlap, inaccessible controls, unusable text, broken focus/keyboard access and scaling artifacts.

**PASS:** all four scale reports pass, screenshots are unique and candidate/session-bound, and all P0/P1 DPI defects are closed.

## 8. Final six-gate aggregation

**Owner:** Release owner / reviewer.

After GitHub and Windows evidence is complete, run:

```powershell
.\tools\v71-finalize-external-acceptance.ps1
```

Then independently verify the resulting immutable reviewer bundle with the repository verifier. The final ZIP must retain the GitHub connected evidence, digest-verified ingest record, bound MSI/EXE/portable manifest, release `SHA256SUMS`, candidate-integrity JSON + sidecar, nested Windows evidence and consolidated decision JSON/Markdown.

**PASS/closure:** only a successful final candidate-bound aggregation may change MHL-010/011/012/017/018/019 from OPEN to PASS.

## Required external evidence checklist

| Gate | Evidence required | Owner | Status before external run |
|---|---|---|---|
| MHL-010 | GitHub required Fast gate + >=5 PR samples + runtime policy + connected acceptance | DevOps | OPEN_EXTERNAL |
| MHL-011 | Windows DPI 100/125/150/200 reports + real screenshots | Windows QA / Frontend | OPEN_EXTERNAL |
| MHL-012 | Bound MSI/EXE/portable lifecycle + real previous MSI upgrade | Windows QA / DevOps | OPEN_EXTERNAL |
| MHL-017 | CycloneDX `bom.json` + `bom.xml` from exact CI Release | DevOps / Security | OPEN_EXTERNAL |
| MHL-018 | Dependency-Check JSON/SARIF/HTML under blocking policy | Security | OPEN_EXTERNAL |
| MHL-019 | Successful exact-candidate CodeQL + no blocking High/Critical alerts | Security | OPEN_EXTERNAL |

## Non-blocking post-release-candidate smokes

These do **not** replace the six gates above:

- MHL-508: audiobook discovery/playback smoke on real Windows and macOS hosts;
- MHL-509: open exported Markdown in installed Obsidian and Joplin;
- MHL-510: smoke an explicitly approved third-party AI provider plugin without changing core consent/SecretStore boundaries.


---

## Superseded external test plan — Iteration 80

**Original source:** `docs/release/EXTERNAL-TEST-PLAN-ITERATION-80.md`

# MyHomeLib — External test plan after Iteration 80

Date: 2026-09-13

Purpose: close only the six gates that cannot be proven locally. The local source is `READY_FOR_LIVE_EVIDENCE`; this plan starts after the Iteration 80 source candidate is frozen in Git.

## 0. Candidate freeze — release owner

**Environment:** Git repository + release owner workstation.

1. Use the formal release identity from `pom.xml` (currently `8.0.0`); changing it invalidates candidate-bound evidence.
2. Commit all approved changes, including the version decision.
3. Record the exact candidate Git SHA.
4. Run `python3 tools/external-acceptance-readiness.py --run-regressions` and require `READY_FOR_LIVE_EVIDENCE` with all six gates still `OPEN_EXTERNAL`.
5. Do not amend/rebase/rebuild the candidate after external evidence collection starts.

**PASS evidence:** candidate SHA, formal version, readiness JSON/Markdown.

**Reset rule:** any source/version/build-policy change after freeze creates a new candidate SHA and invalidates all candidate-bound evidence collected for the old SHA.

## 1. MHL-010 — GitHub PR CI enforcement and runtime

**Owner:** DevOps / repository administrator.  
**Environment:** real GitHub repository.

1. Confirm repository rules/branch protection require the **Fast gate** status check.
2. Obtain at least 5 successful pull-request Fast-gate samples as required by connected acceptance.
3. Confirm median Fast-gate runtime is <= 600 seconds.
4. Run/dispatch the connected acceptance workflow for the exact candidate SHA.

**PASS:** required check is enforced, sample policy passes, exact candidate SHA is accepted by GitHub connected acceptance.

**Save:** repository/rules evidence, workflow run IDs, GitHub connected acceptance JSON/Markdown.

## 2. MHL-017 — live SBOM

**Owner:** DevOps/Security.  
**Environment:** GitHub CI Release for the same candidate SHA.

1. Run `.github/workflows/ci-release.yml` on the exact candidate.
2. Require successful CycloneDX generation.
3. Verify `bom.json` and `bom.xml` are present in `myhomelib-supply-chain`.
4. Preserve the GitHub Actions artifact digest.

**PASS:** both SBOM formats are present, non-empty, generated by the successful exact-candidate release run, and the artifact digest verifies.

## 3. MHL-018 — dependency vulnerability scan

**Owner:** Security / DevOps.  
**Environment:** the same CI Release.

1. Require Dependency-Check JSON/SARIF/HTML artifacts.
2. Confirm the configured CVSS >= 7.0 blocking policy was active.
3. Review suppressions; every active suppression must have justification and expiry according to repository policy.

**PASS:** CI release is not bypassed, no prohibited vulnerability remains, reports are preserved in the exact-candidate supply-chain artifact.

## 4. MHL-019 — CodeQL / SAST

**Owner:** Security.  
**Environment:** GitHub CodeQL + the same candidate SHA.

1. Require a successful CodeQL analysis for the exact candidate.
2. Confirm the CodeQL release-gate record is in the supply-chain artifact.
3. Confirm there are no open blocking High/Critical alerts under the release policy.

**PASS:** candidate-bound CodeQL gate passes and GitHub connected acceptance accepts it.

## 5. Windows source startup gate before full acceptance

**Owner:** Windows QA.  
**Environment:** the exact Iteration 80 candidate checkout on the intended Windows test host.

Before spending time on MSI/DPI evidence, prove that the complete Spring/JavaFX application reaches the main window from freshly installed reactor artifacts:

```powershell
mvn clean install -DskipTests
mvn javafx:run -pl myhomelib-bootstrap
```

The first command is intentional: `javafx:run -pl myhomelib-bootstrap` resolves sibling modules at the current Maven project version from the local Maven repository, so a mere `package` can leave an older local `.m2` JAR in use.

**PASS:** Spring context completes initialization, the main MyHomeLib window opens, and there is no `No default constructor found`, `NoSuchMethodException ... <init>()`, `Cannot subclass final class`, or other bean-creation failure. Save the console log.

**FAIL/reset:** stop Windows acceptance. Do not collect DPI/install PASS evidence from a candidate that cannot complete this startup gate. Any source fix creates a new candidate SHA and restarts the external evidence chain.

## 6. Candidate artifact handoff to Windows

**Owner:** DevOps + Windows QA.  
**Environment:** real Windows machine, standard/non-elevated account, exact candidate checkout.

Use:

```powershell
.\tools\v71-windows-acceptance-start.ps1 `
  -Repo "OWNER/REPO" `
  -AcceptanceRunId <GITHUB_CONNECTED_ACCEPTANCE_RUN_ID> `
  -PreviousMsi <PATH_TO_REAL_PREVIOUS_RELEASE_MSI> `
  -PreviousVersion <REAL_PREVIOUS_VERSION>
```

The script must re-download the connected-acceptance artifact via GitHub, verify the GitHub artifact digest, verify `acceptance-harness.sha256`, preserve `release-candidate-integrity-windows.json` + sidecar + release `SHA256SUMS`, and create one bound Windows host/user/session record.

**Do not use a manually copied/repacked candidate ZIP for final PASS.**

## 7. MHL-012 — Windows packaging lifecycle

**Owner:** Windows QA.  
**Environment:** same bound host/user/session throughout.

Run and record:

1. MSI install as standard user where supported by the packaged mode.
2. First start.
3. Upgrade from the supplied **real previous-release MSI**.
4. Verify user data/settings/collections survive the upgrade.
5. Uninstall and verify documented user-data behavior.
6. Candidate EXE desktop launch/smoke.
7. Portable extraction to a Unicode path as ordinary user.
8. Verify portable data stays inside the portable tree and does not leak to the normal user profile/CWD.

**PASS:** all scripted/manual rows pass on the same candidate/host/user/session; no P0/P1 defect remains.

## 8. MHL-011 — Windows DPI acceptance

**Owner:** Windows QA / Frontend.  
**Environment:** same bound host/user/session and exact candidate.

At each Windows scale **100%, 125%, 150%, 200%**, capture real screenshots of:

- main window / navigation;
- import flow;
- search workspace/results;
- book details;
- Reader (text plus PDF/comic where required by the harness);
- dialogs/settings relevant to release acceptance.

Check for clipping, overlap, inaccessible controls, unusable text, broken focus/keyboard access and scaling artifacts.

**PASS:** all four scale reports pass, screenshots are unique and candidate/session-bound, and all P0/P1 DPI defects are closed.

## 9. Final six-gate aggregation

**Owner:** Release owner / reviewer.

After GitHub and Windows evidence is complete, run:

```powershell
.\tools\v71-finalize-external-acceptance.ps1
```

Then independently verify the resulting immutable reviewer bundle with the repository verifier. The final ZIP must retain the GitHub connected evidence, digest-verified ingest record, bound MSI/EXE/portable manifest, release `SHA256SUMS`, candidate-integrity JSON + sidecar, nested Windows evidence and consolidated decision JSON/Markdown.

**PASS/closure:** only a successful final candidate-bound aggregation may change MHL-010/011/012/017/018/019 from OPEN to PASS.

## Required external evidence checklist

| Gate | Evidence required | Owner | Status before external run |
|---|---|---|---|
| MHL-010 | GitHub required Fast gate + >=5 PR samples + runtime policy + connected acceptance | DevOps | OPEN_EXTERNAL |
| MHL-011 | Windows DPI 100/125/150/200 reports + real screenshots | Windows QA / Frontend | OPEN_EXTERNAL |
| MHL-012 | Bound MSI/EXE/portable lifecycle + real previous MSI upgrade | Windows QA / DevOps | OPEN_EXTERNAL |
| MHL-017 | CycloneDX `bom.json` + `bom.xml` from exact CI Release | DevOps / Security | OPEN_EXTERNAL |
| MHL-018 | Dependency-Check JSON/SARIF/HTML under blocking policy | Security | OPEN_EXTERNAL |
| MHL-019 | Successful exact-candidate CodeQL + no blocking High/Critical alerts | Security | OPEN_EXTERNAL |

## Non-blocking post-release-candidate smokes

These do **not** replace the six gates above:

- MHL-508: audiobook discovery/playback smoke on real Windows and macOS hosts;
- MHL-509: open exported Markdown in installed Obsidian and Joplin;
- MHL-510: smoke an explicitly approved third-party AI provider plugin without changing core consent/SecretStore boundaries.


---

## Historical release/checkpoint notes formerly embedded in MYHOMELIB-RELEASE.md

**Original source:** `MYHOMELIB-RELEASE.md`

## 2026-09-02 refactoring completion note

The source tree has passed the repository's offline architecture, lifecycle, functional, Reader, localization, performance-baseline and static release checks after the stabilization pass. See `docs/history/records/REFACTORING_COMPLETION.md` for the exact source-level baseline. This does not waive the formal release boundary above: compiled Maven verification, platform packaging and real desktop smoke testing are still mandatory before publishing a binary release.

## 2026-09-05 Stage 05 portable-launcher hardening

A real `jpackage` app-image probe found that portable mode could miss `myhomelib2.ini` when the native launcher was started from a working directory different from the launcher directory. `AppPaths.launchDir()` now keeps an explicit `-Dmyhomelib.launchDir` as the highest-priority override, derives the directory of the native process when the `jpackage.app-version` runtime marker is present, and retains `user.dir` as the fallback for ordinary JVM/IDE launches.

The Linux JDK 21 `jpackage` acceptance probe places `myhomelib2.ini` beside `dist/MyHomeLib/bin/MyHomeLib`, starts that launcher from an unrelated working directory, and confirms that runtime directories are created under `dist/MyHomeLib/bin/data` while the normal profile data directory is not created. Windows portable/installer execution, DPI, upgrade and uninstall acceptance remain mandatory before final release.

## Stage 05 P4 Windows DPI acceptance hardening — 2026-09-05

- `tools/windows-ui-acceptance.ps1` now cross-checks the requested 100/125/150/200% run against `GetDpiForSystem()` (96/120/144/192 DPI).
- On a single-monitor acceptance machine, a known system-DPI mismatch is an automatic `AUTO-0 = FAIL`; an unavailable API observation is `BLOCKED`, so the report cannot silently claim PASS.
- On multi-monitor Windows, a system-DPI mismatch is `BLOCKED` rather than a false FAIL because the monitor hosting MyHomeLib can use different per-monitor scaling; P4-01 must confirm that monitor explicitly.
- This is acceptance-tooling hardening only; production Java code is unchanged.

## 2026-09-06 connected GitHub acceptance

The canonical operator sequence for the six remaining external gates is `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md`. The offline `tools/external-acceptance-readiness.py` gate is fail-closed on missing harness/workflow contracts but intentionally reports the six gates as OPEN until live evidence exists.

The legacy-compatible external-evidence harness (historically named “7.1 Final”) for PR enforcement/performance and supply-chain security is collected by `.github/workflows/github-acceptance.yml` using `tools/github-connected-acceptance.py`.

The workflow fails closed unless the default branch actively requires the `Fast gate` status check, at least five successful hosted PR samples have a `Fast gate` median no greater than 600 seconds, and the selected successful `ci-release.yml` run is the exact candidate commit. Its non-expired `myhomelib-supply-chain` and `myhomelib-windows` artifact ZIPs must match the SHA-256 digests declared by the GitHub Actions API. The supply-chain artifact must contain CycloneDX 1.6 JSON/XML, Dependency-Check JSON/SARIF/HTML, and a PASS CodeQL release-gate record for that exact candidate. The same candidate must also have a recent successful CodeQL analysis on the default branch with no open High/Critical code-scanning alerts.

Release CI invokes the same tested CodeQL implementation with `--codeql-release-gate-only --expected-sha "$GITHUB_SHA"`; it fails closed when the exact release candidate has no successful CodeQL analysis yet. Its JSON/Markdown evidence is retained inside the release supply-chain artifact. An offline source archive still cannot claim this connected PASS: the authoritative evidence is produced by real GitHub workflow/API state.

### Legacy-compatible external evidence decision

For the final Windows handoff, prefer `tools/v71-windows-acceptance-start.ps1`. Given the repository name, the exact successful **GitHub connected acceptance** run id, a real previous-release MSI and its version, it downloads the acceptance artifact through the GitHub Actions API, verifies the API-declared SHA-256 digest, safely stages the exact MSI/EXE/portable candidate set, runs the real-previous MSI + portable lifecycle, and launches the interactive real-desktop acceptance. A merely copied local ZIP is insufficient for final PASS because the final gate requires `github-connected-acceptance-ingest.json` with `remoteDigestVerified=true`.

After the four 100/125/150/200% DPI passes exist, run `tools/v71-finalize-external-acceptance.ps1`. The flow revalidates all four GitHub connected checks, the digest-verified GitHub artifact ingest, the strict standard-user/real-previous-MSI/portable lifecycle, the exact candidate EXE desktop smoke, all four DPI reports and the nested Windows evidence ZIP. `tools/v71-final-external-acceptance-check.py` reruns the strict Windows validator against the ZIP payload itself, so a detached or altered reviewer archive cannot pass merely because the live `target` tree passed.

The compatibility finalizer still creates `myhomelib-7.1-final-external-evidence.zip` and invokes `tools/v71-final-evidence-bundle-check.py`; those filenames/output markers are legacy evidence-schema identifiers, not the current product version. The gate verifies the outer sidecar, exact manifest/member set, connected GitHub JSON, GitHub ingest record, three-entry bound candidate manifest (MSI/EXE/portable), nested Windows ZIP + sidecar, desktop/DPI evidence and the consolidated decision record. For Iteration 84, candidate identity must additionally match the version derived from the root `pom.xml` (`8.0.0`).

## Legacy `v71-*` acceptance harness binding

The final Windows acceptance harness is itself candidate-bound. `GitHub connected acceptance` writes `acceptance-harness.sha256` from the exact dispatched candidate checkout. The manifest covers every script that can influence the Windows MHL-011/MHL-012 decision, including ingest, installer/portable, desktop/DPI, evidence validators and final reviewer-bundle checks.

`tools/v71-windows-acceptance-start.ps1` must verify the local checkout against that manifest before any Windows acceptance scenario runs and writes `target/windows-harness-binding/windows-harness-binding.json`. A different/newer/older harness checkout is therefore a hard failure even when the MSI/EXE/portable candidate hashes are correct. The final external gate and reviewer bundle revalidate the manifest hash, the binding record and the exact manifest file/member hash set.

The desktop acceptance runner also launches the already SHA-256-verified bound EXE itself for P5-01; the tester no longer manually chooses an installer executable.

### Candidate-bound Windows host/session evidence

Final Windows evidence must belong to one machine, one Windows user and one acceptance session. `tools/v71-windows-acceptance-start.ps1` clears stale Windows/DPI outputs and creates `target/windows-host-binding/windows-host-binding.json` before installer/portable/desktop evidence is produced. The binding stores a random session id plus one-way SHA-256 fingerprints derived from Windows MachineGuid and the current user SID; raw MachineGuid/SID values are not written to evidence.

Installer, portable, desktop and all four DPI reports carry the same session/host/user fingerprints. `windows-acceptance-evidence-check.py --require-host-binding` fails closed if reports from different machines, users or sessions are combined. The nested Windows evidence ZIP and the final reviewer bundle both retain and independently cross-check this binding against the exact GitHub candidate and connected-acceptance run. Re-running `v71-windows-acceptance-start.ps1` creates a new session and intentionally invalidates any earlier DPI reports.

The validator also requires timezone-aware report timestamps that do not predate the current `windows-host-binding`. Final evidence is a **closed set**: installer logs and screenshot files must be referenced by their report JSON, nested Windows evidence rejects unreferenced extras, and the outer reviewer bundle accepts only its documented exact member set. All acceptance ZIP readers share bounded archive guards for traversal/drive paths, normalized-name collisions, encrypted/symlink/special members and uncompressed-size/file-count limits.



## Iteration 70 local release evidence — MHL-509

- Knowledge Markdown exporter targeted tests: 9/9 PASS.
- Annotation Manager UI contract: 2/2 PASS.
- SQLite annotation export projection integration: 1/1 PASS.
- Full Application: 277 tests, 0 failures, 0 errors, 1 skipped.
- `LayerArchitectureTest`: 14/14 PASS.
- architecture/completeness/localization/static-release/supply-chain gates: PASS.
- full 16-project offline `test-compile`: BUILD SUCCESS.

These are local/source gates. The six external Windows/GitHub acceptance items remain separate and unchanged.


## Iteration 71 local release evidence — MHL-510

- AI host consent/secret contract: 7/7 PASS.
- AI plugin security contract: 3/3 PASS.
- Plugin SPI public surface: 4/4 PASS; full Plugin API: 28/28 PASS.
- Full Application: 284 tests, 0 failures, 0 errors, 1 skipped.
- `LayerArchitectureTest`: 14/14 PASS.
- architecture/completeness/localization/static-release/supply-chain gates: PASS.

Plugin API 1.3 adds `AI_PROVIDER` additively. Iteration 71 established the provider-neutral consent/secret boundary; the current 8.0 tree additionally contains an optional built-in OpenAI Responses adapter. It is not operational until credentials are configured and the user approves content/network sharing for the invocation. Provider credentials remain namespaced through the existing `SecretStore` abstraction rather than plaintext settings. These are local/source gates; the external Windows/GitHub acceptance items remain separate and unchanged.


## Iteration 72 — 8.0 release hardening / split full-regression baseline

Iteration 72 changes no production Java behavior. It closes release-test debt discovered only after the 8.0 feature backlog was complete:

- `ComicReaderWorkflowContractTest` now reflects the already-correct audiobook guard (`!currentAudio`) when asserting text-annotation refresh behavior.
- `BackupRestoreJourneyE2ETest` creates the current V59+ `reading_progress.last_device` column, matching the schema contract that the production backup adapter runs against after Flyway migration.
- the missing deterministic `golden/reader-rich.zip` Reader fixture is restored together with its synthetic second FB2 member; Reader golden ZIP regression is therefore runnable from the formal source package.
- Reader Surefire runs with `java.awt.headless=true`, which keeps PDFBox raster tests independent of a live X11 display while leaving production runtime flags unchanged.
- active plugin-development documentation now identifies Plugin API 1.3 as current.

Validation on the final hardening source:

- split full regression across all 15 child modules: **1,035 tests, 0 failures, 0 errors, 12 skips**;
- Infrastructure exhaustive split: all 132 test classes covered; aggregate reports **432 tests, 0 failures, 0 errors, 7 skips**;
- Reader: **77 tests, 0 failures, 0 errors, 1 skip** without an X11 server;
- UI: **93/93 PASS**; E2E: **14/14 PASS**; Bootstrap: **17/17 PASS**; OPDS: **20/20 PASS**; MCP: **6/6 PASS**; Web: **6/6 PASS**;
- Plugin API: **28/28 PASS**; Application: **284 tests, 0 failures, 0 errors, 1 skip**;
- `LayerArchitectureTest`: **14/14 PASS**;
- architecture, implementation-completeness, critical UI localization, static-release and supply-chain checks: **PASS**;
- full 16-project offline `test-compile`: **BUILD SUCCESS**.

A monolithic root `mvn test` was also attempted. It completed Shared, Domain, Application, Plugin API and plugin samples without failures and entered Infrastructure, but the available foreground execution window ended before the reactor completed. Therefore Iteration 72 deliberately does **not** claim a new monolithic full-reactor PASS; the split full-regression evidence above is the authoritative local baseline for this checkpoint. This does not change or waive MHL-010/011/012/017/018/019, which still require real Windows/GitHub evidence.

## Iteration 73 — external acceptance handoff hardening

Iteration 73 changes no production Java behavior and does not close any external gate. It refreshes `docs/release/CURRENT-VALIDATION.md`, adds the canonical operator sequence in `docs/release/EXTERNAL-ACCEPTANCE-RUNBOOK.md`, and adds `tools/external-acceptance-readiness.py` plus regression coverage to PR CI.

The readiness tool verifies that the PR/release/CodeQL/connected-acceptance workflows, candidate-bound Windows harness and final evidence validators are present and internally consistent. With `--run-regressions` it executes all seven evidence-policy regression scripts. Its only successful overall state is `READY_FOR_LIVE_EVIDENCE`; MHL-010/011/012/017/018/019 remain `OPEN_EXTERNAL` until real GitHub/Windows evidence is collected and the final candidate-bound aggregator passes.

## Iteration 74 — release-candidate integrity binding

Iteration 74 changes no production Java behavior and does not close any external gate. `tools/release-candidate-integrity.py` generates a platform-specific integrity record only after `dist/SHA256SUMS` exists and has been validated. The record binds the exact Git candidate SHA, Maven release identity, a deterministic digest of the Maven-runtime-free source tree, hashes of critical release-policy files, and every checksummed release payload file with size + SHA-256. A `dist/` file not represented by `SHA256SUMS`, a checksum mismatch, or a malformed candidate SHA fails closed.

`CI Release` generates and immediately re-verifies `release-candidate-integrity-linux.json`, `release-candidate-integrity-windows.json` and `release-candidate-integrity-macos.json` before platform artifacts are uploaded. `github-connected-acceptance.py` now requires the Windows integrity JSON + sidecar and independently verifies the exact CI Release `head_sha`, version/platform, `SHA256SUMS` digest, closed dist file set and MSI/EXE/portable sizes/digests before Windows evidence can be staged.

This record is **not** a cryptographic signature, SLSA provenance statement or external acceptance evidence. It is a deterministic SHA-256 integrity binding that strengthens candidate handoff. MHL-010/011/012/017/018/019 still require real GitHub/Windows evidence and remain OPEN until the final candidate-bound aggregator passes.

## Iteration 75 — self-contained final reviewer evidence binding

Iteration 75 changes no production Java behavior and does not close any external gate. It extends the Iteration 74 candidate-integrity chain through the **final reviewer bundle**, so the last offline verifier no longer trusts only derived integrity fields from `github-connected-acceptance.json`.

`tools/github-connected-acceptance.py` now preserves the exact Windows release integrity JSON, its SHA-256 sidecar and the original release `SHA256SUMS` when staging candidate evidence. `tools/github-acceptance-artifact-ingest.py` fail-closes unless those files are present, match the remotely digest-verified connected-acceptance artifact, and are semantically consistent with the candidate manifest and GitHub evidence. The ingest record carries the same integrity/checksum digests forward.

`tools/v71-finalize-external-acceptance.ps1` embeds those exact files into `myhomelib-7.1-final-external-evidence.zip`. `tools/v71-final-evidence-bundle-check.py` then independently verifies the integrity sidecar, candidate SHA, formal version, source-tree digest, release `SHA256SUMS`, deterministic dist-manifest digest and candidate MSI/EXE/portable hashes before accepting the immutable reviewer package. Tampering with the integrity JSON fails even when its sidecar and the outer bundle manifest are recomputed.

The integrity JSON remains a hash-bound evidence record, not a cryptographic signature or SLSA provenance statement. Real GitHub Actions, CodeQL/SBOM/SCA and Windows DPI/packaging evidence are still required for MHL-010/011/012/017/018/019.

## Iteration 76 — reviewer Markdown/JSON consistency hardening

Iteration 76 changes no production Java behavior and does not close any external gate. The final reviewer ZIP already carried machine-readable GitHub/final-decision JSON plus human-readable Markdown, but the offline verifier previously validated only the JSON semantics and outer hashes. A reviewer-facing `.md` file could therefore contradict the JSON if an attacker or broken post-processing step rewrote the ZIP and recomputed its unsiged hash manifests.

`tools/v71-final-evidence-bundle-check.py` now reconstructs the canonical GitHub connected-acceptance Markdown and final external-decision Markdown directly from the embedded JSON and requires byte-for-byte UTF-8 equality. This covers repository/branch/API version, candidate SHA, harness digest, overall status, every GitHub check row and every final evidence status row. Extra or contradictory reviewer text fails closed even when the outer `manifest.sha256` and ZIP sidecar are recomputed.

The reviewer-bundle regression suite includes dedicated tampering cases for both Markdown files. External readiness ratchets both checks, raising the local readiness baseline to **23/23 checks PASS** with the same **8/8 evidence-policy regressions PASS**. This remains offline evidence hardening only: MHL-010/011/012/017/018/019 still require real GitHub/Windows evidence.



## Iteration 77 — versioned external-evidence contract registry

Iteration 77 changes no production Java behavior and closes no external gate. The external acceptance chain already used `schemaVersion` fields, but expected versions were duplicated across individual scripts. That creates release-review drift risk: a producer and verifier can evolve independently, or a future/legacy JSON shape can be accepted by one stage while another stage expects a different contract.

`tools/evidence_contracts.py` is now the single fail-closed registry for the supported evidence scenarios and current schema versions. Release-candidate integrity, GitHub connected acceptance, artifact ingest, Windows harness/host reports, final external decision and readiness records use the registry. Producers derive the current version rather than repeating a literal, while verifiers reject missing/non-integer, legacy, future or wrong-scenario records until an explicit reviewed schema bump changes the registry and regression fixtures. No automatic release-evidence migration is performed.

The registry is included in both the release-candidate `criticalPolicyFiles` digest and the Windows acceptance-harness manifest. `tools/evidence-contracts-test.py` exercises all registered scenarios and future/legacy/missing-schema failure paths. External readiness ratchets the registry structure plus this ninth evidence-policy regression, raising the local baseline to **25/25 checks PASS** with **9/9 evidence-policy regressions PASS**. Real GitHub/Windows evidence remains mandatory for MHL-010/011/012/017/018/019.

## Iteration 78 — final local preflight

Iteration 78 is the final locally actionable preflight before live external acceptance. Unlike Iterations 73–77, it includes one production hardening change: `ContentIndexingQueueService` replaces `Executors.newFixedThreadPool` (which carries an implicit unbounded queue) with an owned zero-queue `ThreadPoolExecutor` using `SynchronousQueue` + `AbortPolicy`; the service's existing active-work cap remains the submission bound.

The complete split Java baseline is revalidated on the Iteration 78 tree: **1,035 tests, 0 failures, 0 errors, 12 skipped**. Infrastructure is exhaustive by split execution (**432/0/0/7**), including watcher/monitor tests separately. Application is **284/0/0/1**, Reader **77/0/0/1**, UI **93/0/0/0**, E2E **14/0/0/0**, Bootstrap **17/17**, Architecture **14/14**, OPDS **20/20**, MCP **6/6**, Web **6/6**, with three benchmark environment skips.

The local check sweep reports **84 PASS** and exactly **3 NEED_EXTERNAL_INPUT** checks; those three validate evidence that can only exist after the real Windows/GitHub run. Offline acceptance and external-readiness regressions pass; readiness remains **25/25 checks PASS / 9/9 evidence-policy regressions PASS** and `READY_FOR_LIVE_EVIDENCE`.

Linux release packaging is also exercised end-to-end after the split test baseline: production package build PASS, `jpackage` app-image launcher smoke PASS, extracted portable archive smoke PASS, deterministic `SHA256SUMS` generation PASS, and Stage23 portable/release-artifact validation PASS. This evidence proves the Linux packaging path only and does not close Windows/GitHub gates.

Historical Stage25 size ratchets and several old source-shape assertions were recalibrated to the reviewed post-feature architecture while preserving behavioral/extraction contracts; this is test-harness maintenance, not a claim of additional class-size refactoring.

The operational external plan is `docs/release/EXTERNAL-TEST-PLAN.md`. External MHL-010/011/012/017/018/019 remain OPEN.


## Iteration 79 — Windows Spring wiring startup hotfix

Real Windows execution of the Iteration 78 candidate exposed a startup blocker before manual acceptance could begin. `ContentIndexingQueueService` had two constructors after the Iteration 78 bounded-worker hardening: the normal four-dependency production constructor and a package-private five-argument constructor used by deterministic tests. With multiple constructor candidates and no explicit injection marker, Spring fell back to no-arg instantiation and failed with `NoSuchMethodException: ContentIndexingQueueService.<init>()`.

Iteration 79 fixes the actual production wiring instead of weakening the Windows harness: the four-dependency constructor is explicitly annotated for Spring injection. `ContentIndexingQueueServiceSpringWiringTest` creates the service through a real `AnnotationConfigApplicationContext`, preserving the package-private deterministic-test constructor while proving that Spring selects the production constructor.

Validation on the hotfix source:

- queue behavior + Spring wiring regression: **5/5 PASS**;
- full Application suite: **285 tests, 0 failures, 0 errors, 1 skip**;
- Bootstrap startup-task suite: **17/17 PASS**;
- `LayerArchitectureTest`: **14/14 PASS**.

Because production source changed, any Iteration 78 candidate-bound GitHub/Windows evidence is no longer valid for final acceptance. A new candidate SHA must be frozen after Iteration 79 and all six external gates remain OPEN until evidence is collected for that exact new SHA.


## Iteration 80 — full Spring runtime wiring hardening

A second real Windows launch after the Iteration 79 `ContentIndexingQueueService` hotfix progressed farther into Spring initialization and exposed another runtime-only constructor-selection defect: `OpdsAccessTokenService` had a normal production constructor plus a deterministic-test constructor but no explicit injection marker, so Spring attempted a missing no-arg constructor. Iteration 80 explicitly marks the production `ApplicationSettingsPort` constructor for injection and adds `OpdsAccessTokenServiceSpringWiringTest`.

Instead of waiting for another one-bean-at-a-time Windows failure, Iteration 80 adds a full eager `SpringContextStartupSmokeTest` in `myhomelib-bootstrap`. That test immediately found two additional runtime startup risks: `SqliteContinueReadingRepository` and `SqliteBookQueryRepository` were `final @Repository` classes and therefore could not be subclassed by Spring's class-based exception-translation proxies. Both repositories are now proxyable. The complete non-web Spring context now refreshes and closes successfully.

Two permanent source policies are added to PR CI: `spring-constructor-wiring-check.py` rejects ambiguous Spring stereotype constructors that lack either a no-arg path or explicit injection constructor, and `spring-proxyability-check.py` rejects final Spring classes that require class-based proxying. PR CI also runs the full Spring-context startup smoke.

Final local validation for this checkpoint:

- exhaustive split baseline: **1,038 tests, 0 failures, 0 errors, 12 skipped**;
- Infrastructure: **432/0/0/7**; Application: **286/0/0/1**; Reader: **77/0/0/1**; UI: **93/93**; OPDS: **20/20**; Bootstrap: **18/18**; Architecture: **14/14**; E2E: **14/14**;
- `OpdsAccessTokenService` wiring + functional token tests: **5/5 PASS**;
- affected SQLite repository tests: **6/6 PASS**;
- full eager Spring context: **1/1 PASS**;
- constructor-wiring and proxyability static policies: **PASS**.

Because Iteration 80 changes production source, Iteration 78/79 candidate-bound external evidence cannot be reused. Freeze a new Iteration 80 candidate SHA, rerun live GitHub gates, then restart Windows acceptance on that exact SHA. MHL-010/011/012/017/018/019 remain `OPEN_EXTERNAL`.

## Iteration 81 — Windows export/TTS usability hotfix

Real Windows testing after the Iteration 80 startup hardening exposed two user-visible defects after the application could progress into normal use. First, a request whose selected format was `FB2_ZIP` could still resolve a later device-profile fallback such as direct `FB2` before trying the converter for the selected format. The log therefore reported `format FB2_ZIP` while the committed target ended in `.fb2`. Export resolution now honors the preference order **per format**: existing direct artifact first, then a compatible converter, and only then legacy raw-copy fallback. This preserves crash-safe converter behavior while ensuring an explicitly selected `FB2_ZIP` is actually converted to a ZIP before considering a later `FB2` fallback.

Second, the Reader TTS voice chooser no longer relies on Java object rendering. It presents explicit labels from voice display name plus language tag, and duplicate labels receive deterministic numeric suffixes. This prevents implementation identities such as `SystemTtsProvider$...@...` from reaching the user even if a provider changes its internal voice object implementation.

Regression coverage added in this checkpoint:

- `ExportToDeviceUseCaseDeviceProfileTest`: selected `FB2_ZIP` must invoke the converter before later direct `FB2` fallback, commit a `.fb2.zip`, and preserve a real FB2 entry inside the archive;
- `Fb2ZipBookConverterTest`: the production converter itself creates a readable ZIP containing one `.fb2` entry;
- `TtsVoicePresentationTest`: voice labels are human-readable, do not expose object identity text, and duplicate labels are disambiguated.

Validation on the Iteration 81 tree:

- exhaustive split baseline: **1,042 tests, 0 failures, 0 errors, 12 skipped**;
- Application: **287/0/0/1**; Infrastructure: **433/0/0/7**; UI: **95/95**; Reader: **77/0/0/1**; Bootstrap: **18/18**; OPDS: **20/20**; E2E: **14/14**; Architecture: **14/14**;
- targeted production `Fb2ZipBookConverter` + system TTS provider: **4/4 PASS**;
- full eager Spring context startup: **1/1 PASS**;
- architecture/completeness/localization/static-release/supply-chain/Spring-wiring/proxyability source gates: **PASS**.

Because Iteration 81 changes production source, Iteration 80 candidate-bound GitHub/Windows evidence cannot be reused. Freeze a new Iteration 81 candidate SHA and restart live external acceptance on that exact candidate. MHL-010/011/012/017/018/019 remain `OPEN_EXTERNAL`.

## Iteration 82 — Windows workflow/state hotfix

Further real Windows testing after Iteration 81 exposed three workflow defects that unit-only/local presentation checks had not fully represented. First, Windows PowerShell 5.1 could corrupt Unicode voice names when redirected stdout used the active OEM/console code page. `SystemTtsProvider` now emits each Windows voice name as UTF-8 Base64 in an ASCII-only discovery row and decodes it in Java; malformed Base64 rows are discarded instead of leaking garbage into the UI.

Second, successful mass export of remote/not-yet-local books behaved correctly at the file layer but left stale UI state: downloaded/local status could remain unchanged and consumed checkbox selection stayed checked. Export completion is now an explicit UI transaction: only a successful non-cancelled export clears the exported checkbox ids and invokes a workspace refresh callback. Main/tree workspaces refresh local status, while Author Workspace reloads the current author and restores the previously selected book when still visible. Failed/cancelled exports deliberately keep selection for retry.

Third, the global Annotations/Notes command bypassed the normal Reader-safe navigation coordinator. It now releases an active Reader before loading the Annotation Manager workspace, with an ordered-interaction regression proving cleanup precedes workspace switch.

Validation on the Iteration 82 tree:

- exhaustive split baseline: **1,048 tests, 0 failures, 0 errors, 12 skipped**;
- Infrastructure: **436/0/0/7**; Application: **287/0/0/1**; UI: **98/98**; Reader: **77/0/0/1**;
- Bootstrap: **18/18** including eager Spring-context smoke; OPDS **20/20**; E2E **14/14**; Architecture **14/14**;
- Windows TTS provider targeted regressions: **6/6 PASS**;
- mass-export completion + Annotation/Notes navigation targeted regressions: **6/6 PASS**.

Because Iteration 82 changes production source, Iteration 81 candidate-bound evidence cannot be reused. Freeze a new Iteration 82 candidate SHA and rerun the six live external gates MHL-010/011/012/017/018/019 against that exact candidate.

## Iteration 83 — Windows TTS discovery + Annotation Manager FXML hotfix

Further real Windows testing after Iteration 82 clarified two remaining defects. The TTS chooser was receiving PowerShell parser diagnostics and fragments of the discovery script through a merged stdout/stderr stream; those lines were then accepted as voice records. Windows voice discovery now uses PowerShell `-EncodedCommand` (UTF-16LE Base64 script transport), emits only strict `MHLVOICE|<UTF-8 Base64 name>|<locale>` rows, parses only that framing, keeps stderr separate and treats every non-zero discovery exit as an I/O failure instead of UI data.

Annotations/Notes navigation itself was already Reader-safe, but the actual Annotation Manager FXML still failed to load on JavaFX 21 because the string `CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` cannot be coerced into the `Callback`-typed `columnResizePolicy` property. The FXML literal is removed; `TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN` is now applied programmatically in `AnnotationManagerWorkspaceController`. A source-level contract prevents the invalid literal from returning, and a display-capable JavaFX `FXMLLoader` regression exercises the real FXML load path.

Validation on the Iteration 83 tree:

- exhaustive split baseline: **1,049 tests, 0 failures, 0 errors, 12 skipped**;
- Infrastructure: **437/0/0/7**; Application: **287/0/0/1**; UI: **98/98**; Reader: **77/0/0/1**;
- Windows TTS provider targeted regressions: **7/7 PASS**;
- Annotation Manager source/FXML contract: **3/3 PASS** locally; display-capable `FXMLLoader` test is present for Windows/desktop CI;
- full eager Spring context: **1/1 PASS**; E2E: **14/14 PASS**; Architecture: **14/14 PASS**;
- architecture/completeness/localization/static-release/supply-chain/Spring-wiring/proxyability gates: **PASS**;
- external-acceptance readiness: **25/25 checks + 9/9 regressions PASS**, overall `READY_FOR_LIVE_EVIDENCE`;
- clean-source offline `test-compile`: **16/16 Maven projects BUILD SUCCESS**.

Because Iteration 83 changes production source, Iteration 82 candidate-bound evidence cannot be reused. Freeze a new Iteration 83 candidate SHA and rerun all six live external gates MHL-010/011/012/017/018/019 against that exact candidate.


---

## Final local audit closure — 2026-09-18

**Original source:** former closure section of `docs/release/WHAT-REMAINS-UA.md`

Фінальна локальна перевірка після всіх виправлень підтвердила:

- **JavaFX 21.0.12 Linux x86-64:** повний offline-набір `base`, `graphics`, `controls`, `fxml`, `media`, `web` перевірений; native `.so` — ELF x86-64; Maven використовує точну production-версію **21.0.12 без override на 21.0.2**;
- **desktop localization:** **3352 синхронні ключі UK/EN/BG**, JavaFX alphabetic UI-sink hardcoded literals — **0**, literal `tr("...")` — **0**;
- **web/OPDS localization:** **74 синхронні ключі**;
- виправлено AI-localization: OpenAI provider більше не примушує українську мову для EN/BG і відповідає мовою фактичного запиту користувача;
- виправлено локалізацію джерела bundled-плагіна в Integration Center: backend повертає семантичний код, UI локалізує його для UK/EN/BG;
- вбудовану довідку UK/EN/BG синхронізовано з реальною функціональністю; виправлено застарілі описи WebDAV, Plugin SPI, AI, backup/restore, INPX, HLC2, search, OPDS, Reader, export/Calibre та інших сценаріїв;
- виправлено реальний Spring wiring defect у `OpenAiResponsesProvider`: injection-конструктор явно позначений `@Autowired`;
- посилено `spring-constructor-wiring-check.py`, який раніше міг хибно розпізнати слово `class` у Javadoc і пропустити неоднозначний Spring-конструктор;
- implementation/static/architecture/startup/supply-chain/user-data/privacy-temp/XML-archive/secret-store/dependency-upgrade перевірки — **PASS**;
- implementation completeness: **1125 production Java files**, **0 TODO/FIXME**, **0 unused explicit imports**, **0 unused Spring constructor dependencies**, **0 exact cross-file clones** за контрольованим порогом;
- закрито функціональні дефекти, знайдені додатковим аудитом: подвійний запуск Calibre, crash-safe INPX export, source/update timestamp semantics, bounded I/O/HTTP, safe filenames, backup/restore generation manifest, транзакційне batch-delete, legacy pseudo-author migration V62, background full-text indexing wiring/race/crash safety, cleanup/switch lifecycle колекцій та Spring wiring `ImportFileUseCase`;
- точковий `SpringContextStartupSmokeTest` після виправлення — **PASS**;
- фінальний повний offline production build: `clean verify -Pproduction` — **17/17 модулів BUILD SUCCESS**;
- фінальний Surefire aggregate: **400 suites, 1189 tests, 0 failures, 0 errors, 12 skipped**;
- 12 пропусків пояснюються явними умовами середовища: 1 Windows-only DPAPI test, 8 real-dataset/performance probes без відповідних system properties, 3 benchmark tests без benchmark properties; це не приховані test failures.

Отже, **локальний source release candidate готовий**. Те, що залишилося вище, є зовнішнім/platform-specific acceptance і не повинно підмінятися штучними доказами в Linux/offline-середовищі.

---

## Documentation consolidation — 2026-09-18

Project documentation was normalized after the final audited source checkpoint:

- the repository root was reduced to the six active canonical Markdown documents;
- parallel history summaries, root iteration/fix reports and Iteration 84/85 release prose were consolidated into this `HISTORY.md`;
- exact raw checkpoint/task/source-note evidence remains under `docs/history/records/` and `docs/history/source-notes/`;
- active release prose was reduced to `CURRENT-VALIDATION.md`, `EXTERNAL-ACCEPTANCE-RUNBOOK.md`, `EXTERNAL-TEST-PLAN.md`, `WHAT-REMAINS-UA.md` and `DEVELOPER-SOURCE-PACKAGE-UA.md`;
- iteration-specific external plans were superseded by the current non-iteration `docs/release/EXTERNAL-TEST-PLAN.md`;
- the build/readiness guards were updated to enforce the new canonical documentation layout.

This reorganization did not change production Java code, runtime resources, Maven project files or build/package scripts.

## V34 External Test R2 — post-R1 regression — 2026-10-03

The post-R1 pass closed the three recorded P0 code defects without opening V35 scope: repeated same-format conversion now reuses a stable derived-artifact row without changing the preferred artifact; re-clicking an already-selected author row redispatches navigation after Operations/Reader; and remote-only Book Details is a normal not-downloaded state with an explicit download action instead of a parser failure. Missing/corrupt local artifacts remain technical local-file states.

Cancellable collection-scoped UI callers now handle synchronous library-operation conflicts/rejections instead of allowing an exception to escape a JavaFX handler. UK/EN/BG localization and the UI action registries were refreshed for the new action. Exact-current UI regression is 370/370 PASS; Application, Infrastructure, Reader, Bootstrap, E2E and Architecture suites were completed in bounded offline runs with no failures/errors. A synthetic memory baseline was recorded, but production pool/cache/Xmx limits were not lowered without same-machine evidence.

A single root `clean verify` is not claimed for R2 because this execution environment forcibly terminates long commands at five minutes; completed module/chunk coverage and that limitation are documented in `docs/release/POST-R1-R2-VALIDATION-2026-10-03-UA.md`. Windows/device/provider/browser live gates remain external.

## V34 External Test R9 — live-acceptance evidence cohesion hardening — 2026-10-04

Without changing production Java/POM/runtime behavior, R9 closes two fail-open risks in post-R5 final aggregation. Windows functional and memory reports must now share one `acceptanceSessionId`, identical host/user fingerprints and OS identity; mixed Windows sessions/hosts are rejected. `--external-final` now validates the real `myhomelib-7.1-final-external-acceptance` schema/backlog/evidence/candidate-binding contract, so a shallow `overall=PASS` JSON cannot close the gate.

RED→GREEN regressions plus readiness/static-release/architecture/completeness/UI/localization/help/supply-chain/hygiene gates pass. Memory defaults are unchanged. No new R9 one-shot Maven PASS is claimed because exact-current attempts were terminated by the external ~5-minute execution limit before reactor summary; frozen R7 **2087/0/0/23, BUILD SUCCESS** remains the unchanged Java baseline until exact-R9 CI/Windows evidence is produced. Details: `docs/release/POST-R8-R9-VALIDATION-2026-10-04-UA.md`.

## V34 External Test R14 — INDEX/DOWNLOAD concurrency + R13 release-audit closure — 2026-10-04

Real use exposed a gap between two operations shown as «Оновлення пошукового індексу»: catalogue Lucene rebuild already used a shared INDEX barrier, while the independent full-text rebuild still acquired exclusive INDEX and blocked online DOWNLOAD. R14 moves full-text rebuild to the same shared INDEX root, defers post-download catalogue/full-text repair off the caller thread while INDEX is active, reference-counts STALE ownership, and removes low-level Lucene rebuild's ability to publish a false fresh marker before application orchestration drains concurrent work.

RED regressions reproduced the exclusive INDEX conflict, synchronous full-text scheduling and stale-hold ownership bug. The final concurrency suite is green, including UI collection-scope submission and real Lucene content-index lifecycle.

A full release audit also found three R13 migration tests still expecting schema 67 after V68. Those stale expectations are updated to 68. Exact-current root offline `clean verify` now completes: **2097 tests, 0 failures, 0 errors, 23 skipped; 21/21 reactor projects SUCCESS; BUILD SUCCESS; 02:38 min**. R13 conversion-primary repair, V68 data repair, EPUB metadata fallback, bundled FBC route and R12 lazy FB2 details regressions remain green.

## V34 External Test R15 — real Windows UX/concurrency/conversion hardening — 2026-10-04

Real Windows R13 evidence showed that the R12 parser optimization did not close the user-visible wait: Book Details still shared workers with online requests that could sit in HTTP timeout/502 paths for minutes. R15 separates NETWORK and INTERACTIVE execution lanes, cancels superseded details work, adds a 5 s deep-analysis watchdog and resource cleanup, and makes production download/details/conversion logs identify book/title/file/route/duration. On all 18 supplied real FB2 books with 24 intentionally blocked network tasks, interactive inspection completed in **751 ms total**; the 17.7 MiB compilation took **241 ms**.

The same audit found why a portable containing `fbc.exe` could still spend ~16.5 min converting: the bundled vendor path was not guaranteed by runtime discovery, allowing AUTO to fall into Calibre. The locator now explicitly recognizes `tools/converters/fbc-windows-amd64/fbc.exe`, and default AUTO FB2/FB2_ZIP→EPUB does not silently use the slow fallback. Conversion/FBC milestones are production-INFO logged. R14 INDEX/DOWNLOAD concurrency and R13 primary/V68/EPUB fixes are retained. Affected regression suites and the Windows-classifier 15-module packaging build pass; R14 remains the last uninterrupted full-root baseline because exact-R15 root runs exceed the environment's 5-minute command cap.

## V34 External Test R16 — second Windows defect-driven orchestration/local-first repair — 2026-10-04

The second real Windows pass exposed that R15 had fixed parser latency without fully fixing operation ownership and stale storage state. The supplied log showed 120–245 s ConnectionScript waits, repeated attempts to materialize books already present on disk, a conversion rejected before converter startup because a passive QUERY owned the shared barrier, disappearing covers after rapid navigation, and a shutdown burst of cancelled author QUERY tasks.

R16 makes same-collection passive/stable operations coexist while destructive collection lifecycle actions still remain serialized; INDEX→DOWNLOAD and QUERY→DOWNLOAD/CONVERSION/INDEX paths are explicitly covered. Download preflight is local-first and rechecks physical storage immediately before HTTP, including the legacy online FB2 archive path and server-renamed ZIP entries. Post-download Lucene/full-text repair is deferred while INDEX is active. Cover work has a cancellable interactive lane with stale-result guards and retryable current-request state. Author workspace requests are latest-request-wins. ConnectionScript read waits are capped to 30 s and automatic GET retries default to zero. Conversion uses a dedicated operation lane, retaining the R15 fail-closed FBC route.

Real supplied corpus evidence: local archive reconciliation **18/18**, cover extraction **18/18**; the large 17.7 MiB compilation cover extraction is ~121 ms in the probe. Exact-current uninterrupted offline root `clean verify`: **2106 tests / 0 failures / 0 errors / 23 skipped; 21/21 reactor projects SUCCESS; BUILD SUCCESS; 02:39 min**. Windows live retest remains required for the exact packaged candidate.
