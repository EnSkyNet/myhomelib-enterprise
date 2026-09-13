# Stage 15 Changelog — User scripts / book actions

Date: 2026-08-25

## Implemented

- Added persisted named `BookActionProfile` objects with ordered `BookActionCommand` entries.
- Each command supports executable, argument template, working directory and optional wait-for-exit behavior.
- Added safe placeholder expansion for `%FILE%`, `%DIR%`, `%FILENAME%`, `%TITLE%`, `%AUTHOR%`, `%SERIES%`, `%LANG%`, `%YEAR%`, `%ISBN%`, `%PUBLISHER%`, `%EXT%`, `%BOOKID%`, `%COLLECTION%` and `%TMP%` plus legacy `%DEST%`/`%DESTFILE%` compatibility.
- Commands run directly with `ProcessBuilder`; no `cmd.exe`, `sh`, shell pipeline or shell re-tokenization is used.
- Added exact argv preview that never executes a process.
- Added quote-safe command-template round-trip including embedded quotes, spaces, Windows paths and UNC paths.
- Added archive-aware `RunBookActionUseCase`; archive entries are materialized through the existing application resource boundary, never by JavaFX controllers.
- Added dynamic book-table context menu populated from enabled action profiles.
- Added profile editor with add/edit/delete/reorder commands and non-destructive preview.
- Existing legacy `export.postCommand` is migrated once into a named profile without deleting the old setting and without implicitly executing malformed content.
- Added JUnit regression tests for command-template boundaries, profile persistence/migration and execution preview semantics.

## Safety/behavior

- Metadata values are substituted only after template tokenization, so a title/path cannot create extra argv entries.
- Detached archive materializations are retained only as temporary JVM-lifetime files; waited commands clean temporary materialization immediately after completion.
- Invalid profiles/arguments are rejected before persistence/execution.
