# Iteration 36 — MHL-203 Reader text selection and annotations

## Scope
- **MHL-203** Text selection and Highlight/Note creation in the desktop Reader.
- Uses MHL-201/MHL-202 domain/persistence from Iteration 35.
- Annotation Manager (MHL-204) remains the next separate workspace task.

## Implementation
- [x] Renderer-neutral `ReaderSelection` snapshot with offsets, quote/context, chapter and paragraph identity.
- [x] Renderer-neutral `ReaderAnnotationOverlay`; Reader has no dependency on annotation domain/SQLite; the UI bridge consumes application annotation DTOs.
- [x] Existing Shift+drag selection retains source-text offsets and now renders draggable endpoint handles.
- [x] Shift+Left/Right keyboard selection.
- [x] Selection context menu: Highlight / Add note / Copy / Clear.
- [x] `Ctrl+Shift+H` and `Ctrl+Shift+N` annotation shortcuts.
- [x] `NewReaderWorkspaceController` wires annotation actions to `AnnotationService` asynchronously.
- [x] Reader artifact binding resolved conservatively from the physical file projection actually opened.
- [x] Persisted annotations loaded after open and rendered from resolved text offsets.
- [x] Exact-offset fast path avoids whole-book materialization; quote/context relocation lazily materializes text only on mismatch.
- [x] Annotation overlays redraw after pagination/reflow and remain anchored to source text.
- [x] UK/EN/BG Reader selection/annotation localization and navigation hints updated.

## Acceptance tests authored (run only after all changes are complete)
- [x] `ReaderSelection` invariants.
- [x] Keyboard selection -> durable quote/context snapshot.
- [x] Selection handle endpoint drag.
- [x] Blank-only selection rejection.
- [x] ReaderSelection -> AnnotationAnchor bridge.
- [x] Exact and contextual persisted-anchor overlay resolution.
- [x] Wrong-artifact overlay suppression.
- [x] UI contract: application service + background executor; no direct SQLite dependency.
- [x] Source gate for MHL-203 wiring, localization and architecture boundary.

## Out of scope
- Annotation Manager search/filter/edit/delete/undo (MHL-204).
- Markdown/JSON/HTML annotation export (MHL-205).
- PDF-specific text-layer annotations (MHL-207).
- Cross-device annotation revisions/tombstones/conflicts (MHL-401+).
