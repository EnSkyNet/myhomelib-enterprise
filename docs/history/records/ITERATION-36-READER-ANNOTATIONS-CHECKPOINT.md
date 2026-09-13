# Iteration 36 checkpoint — MHL-203 Reader annotations

## Delivered

MHL-203 now connects the durable annotation backend to the JavaFX Reader while keeping the Reader module persistence-neutral. Text selections produce source-offset snapshots with quote/context, chapter and paragraph identity. Selection supports Shift+drag, draggable endpoint handles, Shift+Left/Right keyboard extension, a context menu and keyboard shortcuts for Highlight/Note.

The UI bridge converts selection snapshots to application `AnnotationAnchorData` values, saves through `AnnotationService` on `UiBackgroundExecutor`, reloads annotations after reopen, and maps them to lightweight Reader overlays. Exact offsets are checked without creating a full document string; quote/context relocation is the fallback. Overlay rendering occurs on every page render from source offsets, so normal layout/reflow changes do not rewrite anchors.

## Safety rules

- Reader imports no annotation domain/repository/SQLite types.
- DB work does not run on the JavaFX thread.
- A concrete artifact id is assigned only when the opened file projection matches that artifact.
- Artifact-bound annotations are suppressed on a different representation rather than guessed.
- Blank-only selections cannot become annotations.
- Switching/closing a book clears transient selection and overlay state; stale background completions are guarded by the current open-book token.

## Next dependency

MHL-204 can now build an Annotation Manager over the same `AnnotationService`/repository model: filter/search, jump-to-location, edit/delete with confirmation/undo, followed by MHL-205 export.
