# Iteration 02 — Formats, import UI, search facets and path semantics

Backlog scope: **MHL-005, MHL-006, MHL-007, MHL-008** (7.1 Final / P0).

## Implemented

### MHL-005 — single SupportedFormatRegistry
- Added immutable `SupportedFormat` capability model in `myhomelib-shared`: extensions, MIME types, family, import mode, metadata/reader/cover/full-text capabilities and display name.
- Added `SupportedFormatRegistry.standard()` as the runtime source of truth with locale-independent matching and longest compound-extension matching.
- Import detection/dispatch, Reader format extension sets, archive importers, content resolution, cover eligibility, and external-reader settings now derive format decisions from the registry.
- Removed the previous critical duplicated runtime extension lists in these main layers.

### MHL-006 — FileChooser consistency
- Added `ImportFileChooserFilters` generated from the registry.
- Updated `BookImportPresenter`, `ImportWorkspaceController`, `CollectionWizardController`, and collection source selection.
- "All supported" now includes generic imports such as PDF, DjVu, MOBI/AZW/AZW3, DOC/DOCX/ODT, RTF, HTML/XHTML, CHM and supported archives.

### MHL-007 — canonical format in application / SQLite / Lucene
- Extended canonical `BookFormat` values while retaining legacy `FB2ZIP` enum compatibility for saved filters.
- SQLite `BookDenormalizedValues` and Lucene `LuceneDocumentMapper` now use the same registry mapping.
- Added Flyway **V49** to recalculate existing `books.format` values, so upgraded databases do not keep previously collapsed `UNKNOWN` facets.
- Updated migration acceptance from V48 to V49.

### MHL-008 — BookFile.getFullPath
- Fixed loss of `fileName` for relative and absolute folders.
- Added collection-root resolution.
- Added platform-independent handling of POSIX, Windows drive and UNC paths.
- Path joining is textual and Unicode-safe even when tests run under a restrictive host filesystem encoding.

## Regression found and fixed during the iteration
`.md` was accepted by the Reader, classified as a document by the old detector, but rejected by `TxtImporter`. The registry now makes `.txt`, `.text`, and `.md` one TXT capability family and importer behavior is aligned.

## Verification
- Full offline reactor compile: **PASS**.
- Targeted tests: **PASS**:
  - `SupportedFormatRegistryTest`
  - `BookFilePathTest`
  - `BookFormatRegistryConsistencyTest`
  - `BookFormatDetectorLocaleTest`
  - `BookDenormalizedValuesTest`
  - `LuceneDocumentMapperStorageTest`
  - `DatabaseMigrationMatrixTest` (representative historical schemas -> V49)
  - `ImportFileChooserFiltersTest`
- `git diff --check`: **PASS**.

## Compatibility notes
- Existing historical Flyway migrations are unchanged; V49 is additive.
- `BookFormat.FB2ZIP` remains in the enum for compatibility, while new persisted/indexed `.fb2.zip` values canonicalize to `ZIP`.
