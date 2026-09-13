# Iteration 49 — Content extraction, independent full-text index, annotation search

**Дата:** 2026-09-12  
**Статус:** DONE (local acceptance)  
**Scope:** MHL-301, MHL-302, MHL-306

## MHL-301 — ContentExtractor SPI

- Application-owned format-neutral `ContentExtractor` port with typed extraction request/result/status models.
- `ContentExtractionContext` provides cancellation and progress callbacks without depending on UI or Reader.
- Infrastructure adapters cover FB2, EPUB and TXT and return chapters, normalized text and stable anchors.
- EPUB extraction respects spine order and rejects archive-root traversal; FB2 parsing does not resolve external entities.
- Unsupported, cancelled and failed extraction paths are represented by typed results rather than ad-hoc exceptions leaking to callers.

## MHL-302 — Separate Lucene Content Index

- Application-owned `ContentIndexPort` separates content search from the existing catalogue metadata index.
- Infrastructure stores the index under the dedicated per-collection `content-index` path with its own lock/lifecycle domain.
- Documents carry `bookId`, `artifactId`, chapter id/title/text, chapter offsets and indexed token positions/offsets.
- Content-index schema is versioned; incompatible incremental writes fail closed and an independent rebuild repairs the index.
- Rebuild is staged into a candidate directory and swapped into place with rollback/fallback handling.
- Integration tests prove that content rebuilds do not mutate the catalogue search index.
- Opt-in benchmark covers 5,000 documents and records rebuild/search timings while checking a catalogue-index sentinel.

## MHL-306 — Search annotations

- SQLite FTS5 index covers annotation note, quote, chapter, tags and book title.
- Triggers keep the annotation search document synchronized after annotation/anchor/tag/book-title mutations.
- Existing local filters remain available: book, type, color, tag and date range.
- Global Search Workspace shows annotation results and Enter/double-click opens the exact annotation anchor through the existing Reader boundary.
- Search remains local-only; no annotation text is sent to an online provider.

## Local acceptance

- `tools/iteration49-content-search-check.py`: 11/11 PASS.
- MHL-301/MHL-302/MHL-306 focused integration/contract tests: PASS.
- MHL-302 benchmark: >=5,000 documents and catalogue sentinel unchanged.
- Full offline Maven reactor: required green before source packaging.

## External boundary

This iteration does not close MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019. Those remain OPEN until real Windows/GitHub evidence exists.
