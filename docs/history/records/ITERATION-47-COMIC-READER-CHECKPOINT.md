# Iteration 47 — Comic Reader CBZ/CBR checkpoint

**Date:** 2026-09-12  
**Task:** MHL-208  
**Implementation status:** COMPLETE  
**Validation status:** DONE (local technical acceptance)

## Implemented

### One comic container = one catalogue book

CBZ and CBR are declared native reader `BOOK` formats. `ComicArchiveImporter` validates that the container has at least one supported image and creates exactly one `Book`. ZIP and RAR multi-book importers no longer claim `.cbz`/`.cbr`.

### Lazy page session

`ComicDocumentSession` opens by enumerating page names only. It filters unsafe/unsupported members through `ComicPageNameSupport`, sorts names naturally, and reads/decompresses a member only when its page is rendered. ImageIO reads dimensions before decode, enforces a 20M-pixel source ceiling and uses source subsampling for bounded viewport/thumbnail rendering. Individual compressed page reads are capped at 64 MiB; decoded raster cache is a 64 MiB access-ordered LRU.

### JavaFX comic renderer

`ComicReaderView` provides fit-page, fit-width, zoom, continuous scroll, dual-page spreads, RTL manga visual ordering and thumbnails. Render work runs on one background executor. Generation/session tokens and task cancellation prevent stale results from updating a replaced/closed document.

### Workspace/persistence

UI adapts `BookResourcePort` to the Reader-owned `ComicPageSource`; it does not depend on the concrete archive adapter. Reader position uses page index and is reused by autosave, reading progress and bookmarks. Back, replacement and dispose close the comic session. Image comics do not enter text annotation/TOC/search flows.

## Validation — 2026-09-12

- Focused Comic Reader/application/import tests: PASS.
- `ZipArchiveReaderTransparencyTest` + `ComicArchiveImporterTest`: 13/13 PASS.
- `tools/iteration47-comic-reader-check.py`: 8/8 PASS.
- Static release/security/architecture gates: PASS after removing two detected cross-view helper clones.
- Full offline Maven reactor: **13/13 modules BUILD SUCCESS**.
- Surefire total: **820 tests, 0 failures, 0 errors, 10 skipped**.
- Infrastructure: **378 tests, 0 failures, 0 errors, 7 skipped**.
- Reader: **67 tests, 0 failures, 0 errors, 1 skipped**.
- UI: **76 tests, 0 failures, 0 errors, 0 skipped**.
- E2E: **10/10 PASS**.

## Audit correction during validation

The first static completeness cycle detected exact helper duplication between PDF and Comic views (`toFxImage`, tracked render submission). Comic implementations were rewritten to remove the cross-file clone while retaining cancellation/task tracking semantics. No acceptance requirement was weakened.

## External release gates

MHL-010, MHL-011, MHL-012, MHL-017, MHL-018 and MHL-019 remain **OPEN** pending real GitHub/Windows evidence. MHL-208 completion does not imply a release-ready build until those external gates are satisfied.
