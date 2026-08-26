# Stage 16 Changelog — Export/device profiles

Date: 2026-08-25

## Implemented

- Added persisted named `ExportProfile` objects with format, destination, collision policy, extract-only option, filename template, subfolder template and optional Stage-15 post-action profile.
- Added one-time migration of legacy global filename/subfolder/post-command settings into an editable default export profile.
- Added collision policy `ASK` in addition to overwrite/skip/auto-rename. The worker requests a per-file decision from the JavaFX UI without running export work on the UI thread.
- Existing batch progress/cancellation was preserved and connected to the global status bar as well as the export dialog.
- Filename/subfolder templates are now profile-specific. Supported fields include author/title/series/series-number/year/language/publisher/book ID.
- Added destination path containment checks for both generated subfolders and target filenames.
- Fixed the pre-existing `extractOnly` flag: archive entries can now actually be copied raw without conversion when requested.
- Added profile-specific post-export action integration using the Stage-15 safe `BookActionExecutionService`; the old global post-command remains a backward-compatibility fallback only.
- Added bounded persisted export history (last 50 runs) with timestamp, profile, destination, format, requested/exported/skipped/failed/cancelled and duration.
- Added history viewer and profile save/update/delete controls to the export dialog.
- Added JUnit regression tests for profile migration/persistence and export-history ordering/clear behavior.

## Compatibility

- Existing `ExportRequest` callers remain source-compatible; new profile fields are optional.
- Existing rename/overwrite/skip behavior remains unchanged unless `ASK` is selected.
- Legacy export settings are not deleted during migration.
