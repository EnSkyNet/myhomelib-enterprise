# Iteration 59 — Web Reader basic EPUB/FB2

Date: 2026-09-12
Status: DONE locally
Backlog: MHL-409

## Delivered

- Added application-level `WebReaderUseCase` / `WebReaderService` that reuses the existing format-neutral `ContentExtractionService` instead of introducing duplicate EPUB/FB2 parsers.
- Web Reader supports FB2 and EPUB chapters, TOC navigation, responsive reader layout, light/sepia/dark themes and persisted browser font-size preference.
- Reader progress is stored in the existing `reading_progress` repository with a `ReaderPosition`-compatible `anchorId`, so desktop and web use the same position state.
- Reopening the Web Reader resumes the saved chapter/offset; explicit chapter navigation remains available.
- Unsupported formats render a safe download fallback rather than pretending to support browser rendering.
- `/web/read/{bookId}/progress` is authenticated, bounded to 2 KiB JSON, requires the same-origin request marker, validates chapter/offset and stores progress through the application boundary.
- Catalogue/details links now route supported reading actions to `/web/read/{bookId}` while retaining separate details/download actions.

## Verification

- `WebReaderServiceTest`: 3/3 PASS.
- `WebReaderExtractionIntegrationTest`: 2/2 PASS using the real FB2 and EPUB extractor adapters.
- `WebReaderRendererTest`: 2/2 PASS.
- `JdkOpdsServerTest`: 14/14 PASS, including authenticated reader GET, resume state and progress POST marker enforcement.
- Combined targeted MHL-409 acceptance: 21/21 PASS.
- `tools/architecture-check.py`: PASS.
- `tools/implementation-completeness-check.py`: PASS.
- `tools/static_release_check.py`: PASS.
- `tools/stage5-history-check.py`: PASS.
- `git diff --check`: PASS.

## Validation boundary

Iteration 59 does not claim a new full 13-module reactor. The latest full-reactor baseline remains Iteration 55: 13/13 modules BUILD SUCCESS, 907 tests, 0 failures/errors, 12 skipped. External MHL-010/011/012/017/018/019 remain OPEN pending real Windows/GitHub evidence.
