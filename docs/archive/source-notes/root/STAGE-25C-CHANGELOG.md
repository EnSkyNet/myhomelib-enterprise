# Stage 25C Changelog — Lucene Search / Folder Sync Targeted Refactor

## Scope

Stage 25C is the final targeted refactor from the agreed roadmap. It changes internal class boundaries only, except for one verified FolderSync error-count bug fix discovered during the refactor. Stage 24 remains the performance baseline.

## Lucene search refactor

- Reduced `LuceneSearchService` from 662 lines before Stage 25C to 391 lines.
- Extracted immutable catalogue snapshot → Lucene schema mapping to `LuceneDocumentMapper`.
- Extracted application `BookFilterSpec` → Lucene query translation to `LuceneUnifiedFilterBuilder`.
- Extracted classic MyHomeLib query compatibility/normalization to `LuceneQueryNormalizer`.
- Kept `LuceneSearchService` focused on index lifecycle, indexing/rebuild, SearcherManager lifecycle and search execution.
- Preserved:
  - classic English/Ukrainian field aliases;
  - `%contains%` syntax;
  - exact/prefix/fuzzy modes;
  - numeric/date comparisons;
  - SQL/Lucene unified-filter semantics;
  - the existing Lucene document field schema used by Stage 8/9.

## Folder sync refactor

- Reduced `FolderSyncService` from 522 lines before Stage 25C to 339 lines.
- Extracted storage/path normalization and user-state-preserving metadata merge policy to `FolderSyncBookSupport`.
- Extracted helper responsibilities include:
  - loose/archive storage normalization;
  - physical-path resolution;
  - changed-file/archive checks;
  - archive-entry normalization;
  - supported archive/INPX classification;
  - preservation of rating/progress/review/LibID and other user state during metadata refresh.
- `FolderSyncService` remains the orchestration layer for scanning, import/update/reconcile, cancellation, orphan handling and index commit.

## Bug fixed during refactor

The folder scanner `IOException` catch incremented `counters.errors` twice for one scan failure. This is corrected so one scanner failure produces exactly one error and one error message.

## Regression protection

- Added `LuceneQueryNormalizerTest` for aliases/comparisons/contains syntax.
- Added `FolderSyncServiceTest.scannerFailureIsCountedOnce`.
- Added `tools/stage25c-search-sync-refactor-check.py`, including a standalone `javac`/runtime smoke for `LuceneQueryNormalizer` with minimal Lucene stubs.
- Updated the Stage 8/9 filter guard to follow the extracted Lucene responsibilities instead of requiring filter/document logic to remain physically inside `LuceneSearchService`.

No database schema or public application port was changed by Stage 25C.
