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
