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
