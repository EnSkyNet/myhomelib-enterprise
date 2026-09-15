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
