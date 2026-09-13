# Iteration 37 — MHL-204 Annotation Manager

## Scope
- **MHL-204** global Annotation Manager workspace for persisted highlights and notes.
- Builds on MHL-201/MHL-202 persistence and MHL-203 Reader anchor rendering.
- MHL-205 rich Markdown/JSON/HTML annotation export remains the next separate task; Iteration 37 provides filtered UTF-8 CSV export from the manager.

## Implementation
- [x] Application-only manager DTOs/filter/page/facets and bounded `AnnotationManagerService`.
- [x] Dedicated output query port; UI does not query SQLite or annotation domain directly.
- [x] SQLite paged query with search across book title, quote, note, chapter and tags.
- [x] Explicit filters: book / type / color / tag / updated-date range.
- [x] LIKE wildcard escaping so `%` and `_` in user input remain literal search characters.
- [x] Annotation Manager workspace reachable from Tools menu and session navigation history.
- [x] Async refresh and page navigation (100-row UI pages; query hard cap 500).
- [x] Edit note/color/tags through the application boundary.
- [x] Delete confirmation + one-step complete snapshot undo without lossy anchor reconstruction.
- [x] Double-click / Open action jumps to the persisted annotation in Reader after anchor resolution.
- [x] Wrong-artifact/unresolvable Reader target degrades to a localized status message.
- [x] Filtered UTF-8 CSV export streams bounded pages instead of materializing the complete result set.
- [x] UK/EN/BG localization catalogs synchronized.

## Acceptance tests authored (run only after all changes are complete)
- [x] Manager service bounded-query + atomic edit + delete/undo snapshot tests.
- [x] SQLite search/filter/facets/pagination integration test.
- [x] Literal wildcard search regression.
- [x] UI source/FXML contract for filter/jump/edit/delete/undo/export.
- [x] Reader one-shot annotation-target contract.
- [x] Stage 37 architecture/localization/source gate.

## Out of scope
- Markdown/JSON/HTML configurable annotation exporters (MHL-205).
- PDF text-layer annotation integration (MHL-207).
- Annotation full-content Lucene search (MHL-306).
- Cross-device revisions/tombstones/conflicts (MHL-401+).

## Validation policy
- All production code, tests and documentation are completed before Maven/static validation starts.
